package bt.peer;

import bt.metainfo.Torrent;
import bt.metainfo.TorrentId;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.net.PeerId;
import bt.service.IRuntimeLifecycleBinder;
import bt.service.IdentityService;
import bt.torrent.TorrentDescriptor;
import bt.torrent.TorrentRegistry;
import bt.tracker.AnnounceKey;
import bt.tracker.ITrackerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 *<p><b>Note that this class implements a service.
 * Hence, is not a part of the public API and is a subject to change.</b></p>
 */
public class PeerRegistry implements IPeerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerRegistry.class);

    private final Peer localPeer;
    private final PeerCache cache;

    private TorrentRegistry torrentRegistry;
    private ITrackerService trackerService;
    private TrackerPeerSourceFactory trackerPeerSourceFactory;
    private Set<PeerSourceFactory> extraPeerSourceFactories;

    private ConcurrentMap<TorrentId, List<Consumer<Peer>>> peerConsumers;

    private ConcurrentMap<TorrentId, Set<AnnounceKey>> extraAnnounceKeys;
    private ReentrantLock extraAnnounceKeysLock;

    public PeerRegistry(IRuntimeLifecycleBinder lifecycleBinder,
                        IdentityService idService,
                        TorrentRegistry torrentRegistry,
                        ITrackerService trackerService,
                        Set<PeerSourceFactory> extraPeerSourceFactories,
                        InetAddress localPeerAddress,
                        int localPeerPort,
                        Duration peerDiscoveryInterval,
                        Duration trackerQueryInterval) {

        this.peerConsumers = new ConcurrentHashMap<>();
        this.localPeer = new InetPeer(localPeerAddress, localPeerPort, idService.getLocalPeerId());
        this.cache = new PeerCache();

        this.torrentRegistry = torrentRegistry;
        this.trackerService = trackerService;
        this.trackerPeerSourceFactory = new TrackerPeerSourceFactory(trackerService, torrentRegistry, lifecycleBinder, trackerQueryInterval);
        this.extraPeerSourceFactories = extraPeerSourceFactories;

        this.extraAnnounceKeys = new ConcurrentHashMap<>();
        this.extraAnnounceKeysLock = new ReentrantLock();

        createExecutor(lifecycleBinder, peerDiscoveryInterval);
    }

    private void createExecutor(IRuntimeLifecycleBinder lifecycleBinder, Duration peerDiscoveryInterval) {
        ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "bt.peer.peer-collector"));
        lifecycleBinder.onStartup("Schedule periodic peer lookup", () -> executor.scheduleAtFixedRate(
                this::collectAndVisitPeers, 1, peerDiscoveryInterval.toMillis(), TimeUnit.MILLISECONDS));
        lifecycleBinder.onShutdown("Shutdown peer lookup scheduler", executor::shutdownNow);
    }

    private void collectAndVisitPeers() {
        peerConsumers.keySet().forEach(torrentId -> {
            Optional<TorrentDescriptor> descriptor = torrentRegistry.getDescriptor(torrentId);
            if (descriptor.isPresent() && descriptor.get().isActive()) {
                Optional<Torrent> torrentOptional = torrentRegistry.getTorrent(torrentId);

                Optional<AnnounceKey> torrentAnnounceKey = torrentOptional.isPresent() ?
                        torrentOptional.get().getAnnounceKey() : Optional.empty();

                Collection<AnnounceKey> extraTorrentAnnounceKeys = extraAnnounceKeys.get(torrentId);
                if (extraTorrentAnnounceKeys == null) {
                    queryTrackers(torrentId, torrentAnnounceKey, Collections.emptyList());
                } else if (torrentOptional.isPresent() && torrentOptional.get().isPrivate()) {
                    if (extraTorrentAnnounceKeys.size() > 0) {
                        // prevent violating private torrents' rule of "only one tracker"
                        LOGGER.warn("Will not query extra trackers for a private torrent, id: {}", torrentId);
                    }
                } else {
                    // more announce keys might be added at the same time;
                    // querying all trackers can be time-consuming, so we make a copy of the collection
                    // to prevent blocking callers of addPeerSource(TorrentId, AnnounceKey) for too long
                    Collection<AnnounceKey> extraTorrentAnnounceKeysCopy;
                    extraAnnounceKeysLock.lock();
                    try {
                        extraTorrentAnnounceKeysCopy = new ArrayList<>(extraTorrentAnnounceKeys);
                    } finally {
                        extraAnnounceKeysLock.unlock();
                    }
                    queryTrackers(torrentId, torrentAnnounceKey, extraTorrentAnnounceKeysCopy);
                }

                // disallow querying peer sources other than the tracker for private torrents
                if ((!torrentOptional.isPresent() || !torrentOptional.get().isPrivate()) && !extraPeerSourceFactories.isEmpty()) {
                    extraPeerSourceFactories.forEach(factory ->
                            queryPeerSource(torrentId, factory.getPeerSource(torrentId)));
                }
            }
        });
    }

    private void queryTrackers(TorrentId torrentId, Optional<AnnounceKey> torrentAnnounceKey, Collection<AnnounceKey> extraAnnounceKeys) {
        torrentAnnounceKey.ifPresent(announceKey -> {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Querying tracker peer source (announce key: {}) for torrent id: {}", announceKey, torrentId);
            }
            try {
                queryTracker(torrentId, announceKey);
            } catch (Exception e) {
                LOGGER.error("Error when querying tracker (torrent's announce key): " + announceKey, e);
            }
        });
        extraAnnounceKeys.forEach(announceKey -> {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Querying tracker peer source (announce key: {}) for torrent id: {}", announceKey, torrentId);
            }
            try {
                queryTracker(torrentId, announceKey);
            } catch (Exception e) {
                LOGGER.error("Error when querying tracker (extra announce key): " + announceKey, e);
            }
        });
    }

    private void queryTracker(TorrentId torrentId, AnnounceKey announceKey) {
        if (mightCreateTracker(announceKey)) {
            queryPeerSource(torrentId, trackerPeerSourceFactory.getPeerSource(torrentId, announceKey));
        }
    }

    private boolean mightCreateTracker(AnnounceKey announceKey) {
        if (announceKey.isMultiKey()) {
            // TODO: need some more sophisticated solution because some of the trackers might be supported
            for (List<String> tier : announceKey.getTrackerUrls()) {
                for (String trackerUrl : tier) {
                    if (!trackerService.isSupportedProtocol(trackerUrl)) {
                        return false;
                    }
                }
            }
            return true;
        } else {
            return trackerService.isSupportedProtocol(announceKey.getTrackerUrl());
        }
    }

    private void queryPeerSource(TorrentId torrentId, PeerSource peerSource) {
        try {
            if (peerSource.update()) {
                Collection<Peer> discoveredPeers = peerSource.getPeers();
                for (Peer peer : discoveredPeers) {
                    addPeer(torrentId, peer);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error when querying peer source: " + peerSource, e);
        }
    }

    @Override
    public void addPeer(TorrentId torrentId, Peer peer) {
        if (isLocal(peer)) {
            return;
        }
        cache.registerPeer(peer);
        for (Consumer<Peer> consumer : peerConsumers.getOrDefault(torrentId, Collections.emptyList())) {
            try {
                consumer.accept(peer);
            } catch (Exception e) {
                LOGGER.error("Error in peer consumer", e);
            }
        }
    }

    @Override
    public void addPeerSource(TorrentId torrentId, AnnounceKey announceKey) {
        extraAnnounceKeysLock.lock();
        try {
            getOrCreateExtraAnnounceKeys(torrentId).add(announceKey);
        } finally {
            extraAnnounceKeysLock.unlock();
        }
    }

    private Set<AnnounceKey> getOrCreateExtraAnnounceKeys(TorrentId torrentId) {
        Set<AnnounceKey> announceKeys = extraAnnounceKeys.get(torrentId);
        if (announceKeys == null) {
            announceKeys = ConcurrentHashMap.newKeySet();
            Set<AnnounceKey> existing = extraAnnounceKeys.putIfAbsent(torrentId, announceKeys);
            if (existing != null) {
                announceKeys = existing;
            }
        }
        return announceKeys;
    }

    private boolean isLocal(Peer peer) {
        return peer.getInetAddress().isAnyLocalAddress() && localPeer.getPort() == peer.getPort();
    }

    @Override
    public Peer getLocalPeer() {
        return localPeer;
    }

    @Override
    public Peer getPeerForAddress(InetSocketAddress address) {
        return cache.getPeerForAddress(address);
    }

    @Override
    public void addPeerConsumer(Torrent torrent, Consumer<Peer> consumer) {
        addPeerConsumer(torrent.getTorrentId(), consumer);
    }

    @Override
    public void addPeerConsumer(TorrentId torrentId, Consumer<Peer> consumer) {
        List<Consumer<Peer>> consumers = peerConsumers.get(torrentId);
        if (consumers == null) {
            consumers = new ArrayList<>();
            List<Consumer<Peer>> existing = peerConsumers.putIfAbsent(torrentId, consumers);
            if (existing != null) {
                consumers = existing;
            }
        }
        consumers.add(consumer);
    }

    // TODO: someone should call this after torrent is stopped/completed
    @Override
    public void removePeerConsumers(Torrent torrent) {
        removePeerConsumers(torrent.getTorrentId());
    }

    @Override
    public void removePeerConsumers(TorrentId torrentId) {
        peerConsumers.remove(torrentId);
    }

    private static class PeerCache {
        // all known peers (lookup by inet address)
        private final ConcurrentMap<InetSocketAddress, UpdatablePeer> knownPeers;
        private final ReentrantLock peerLock;

        PeerCache() {
            this.knownPeers = new ConcurrentHashMap<>();
            this.peerLock = new ReentrantLock();
        }

        // need to do this atomically:
        // - concurrent call to getPeerForAddress(InetSocketAddress)
        //   might coincide with querying peer sources (overwriting options, etc)
        private UpdatablePeer registerPeer(Peer peer) {
            peerLock.lock();
            try {
                UpdatablePeer newPeer = new UpdatablePeer(peer);
                UpdatablePeer existing = knownPeers.putIfAbsent(peer.getInetSocketAddress(), newPeer);
                if (existing != null) {
                    existing.setOptions(peer.getOptions());
                }
                return (existing == null) ? newPeer : existing;
            } finally {
                peerLock.unlock();
            }
        }

        public Peer getPeerForAddress(InetSocketAddress address) {
            Peer existing = knownPeers.get(address);
            if (existing == null) {
                peerLock.lock();
                try {
                    existing = knownPeers.get(address);
                    if (existing == null) {
                        existing = registerPeer(new InetPeer(address));
                    }
                } finally {
                    peerLock.unlock();
                }
            }
            return existing;
        }
    }

    private static class UpdatablePeer implements Peer {
        private final Peer delegate;
        private volatile PeerOptions options;

        UpdatablePeer(Peer delegate) {
            super();
            this.delegate = delegate;
            this.options = delegate.getOptions();
        }

        @Override
        public InetSocketAddress getInetSocketAddress() {
            return delegate.getInetSocketAddress();
        }

        @Override
        public InetAddress getInetAddress() {
            return delegate.getInetAddress();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public Optional<PeerId> getPeerId() {
            return delegate.getPeerId();
        }

        @Override
        public PeerOptions getOptions() {
            return options;
        }

        void setOptions(PeerOptions options) {
            this.options = options;
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            return delegate.equals(object);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
