/* 文件职责：提供待生成路径的内存前台缓存，并协调文件持久层。 */
package net.shiroha233.roadweaver.planning.path;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.persistence.WorldgenFingerprintService;
import net.shiroha233.roadweaver.persistence.files.FileStoragePathResolver;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久路径仓库的进程内前台。
 */
public final class PlannedPathCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int PATH_WORLDGEN_SCHEMA = 1;
    private static final Map<ServerLevel, ConcurrentHashMap<PlannedPathKey, Entry>> BY_LEVEL =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** Reuse ownership produced by the planning pass, kept beside the path cache. */
    private static final Map<ServerLevel, ConcurrentHashMap<PlannedPathKey, List<ReuseRange>>> REUSE_BY_LEVEL =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private PlannedPathCache() {}

    public static void register(ServerLevel level,
                                TerrainSamplingMode effectiveMode,
                                PathfindingCostConfig config,
                                Map<StructureConnection, List<BlockPos>> paths) {
        if (level == null || effectiveMode == null || config == null || paths == null || paths.isEmpty()) return;
        String computedFingerprint = fingerprintOrNull(level, effectiveMode, config);
        registerInternal(memory(level), store(level), computedFingerprint, paths,
                level.dimension().location());
    }

    /**
     * Memory writes are synchronous and authoritative; persistence is fanned
     * out to a small bounded executor (one worker per initial-generation lane,
     * capped at four) so registering a large planning batch never blocks the
     * planning thread on disk. A failed save keeps the in-memory path and
     * logs; the next planning pass of the same connection rewrites it.
     * Package-private for the batch-write regression.
     */
    static void registerInternal(ConcurrentHashMap<PlannedPathKey, Entry> memory,
                                 PlannedPathStore store,
                                 String fingerprint,
                                 Map<StructureConnection, List<BlockPos>> paths,
                                 Object dimensionIdForLog) {
        boolean persistent = fingerprint != null;
        String effectiveFingerprint = persistent ? fingerprint : "";
        for (Map.Entry<StructureConnection, List<BlockPos>> item : paths.entrySet()) {
            StructureConnection connection = item.getKey();
            List<BlockPos> path = item.getValue();
            if (connection == null || path == null || path.isEmpty()) continue;
            PlannedPathKey key = PlannedPathKey.of(connection);
            List<BlockPos> immutablePath = List.copyOf(path);
            memory.put(key, new Entry(effectiveFingerprint, immutablePath));
            if (persistent) {
                submitPersistence(store, key, effectiveFingerprint, immutablePath, dimensionIdForLog);
            }
        }
    }

    private static void submitPersistence(PlannedPathStore store,
                                          PlannedPathKey key,
                                          String fingerprint,
                                          List<BlockPos> path,
                                          Object dimensionIdForLog) {
        PENDING_WRITES.incrementAndGet();
        writeExecutor().execute(() -> {
            try {
                store.save(key, fingerprint, path);
            } catch (IOException failure) {
                LOGGER.warn("持久化待生成路径失败 dimension={} key={}", dimensionIdForLog, key, failure);
            } catch (Throwable failure) {
                LOGGER.warn("持久化待生成路径意外失败 dimension={} key={}", dimensionIdForLog, key, failure);
            } finally {
                PENDING_WRITES.decrementAndGet();
            }
        });
    }

    private static final AtomicInteger PENDING_WRITES = new AtomicInteger();
    private static volatile ExecutorService WRITE_EXECUTOR;
    private static volatile int WRITE_EXECUTOR_CAPACITY;

    private static ExecutorService writeExecutor() {
        ExecutorService executor = WRITE_EXECUTOR;
        if (executor != null) return executor;
        synchronized (PlannedPathCache.class) {
            if (WRITE_EXECUTOR == null) {
                int workers = Math.max(1, Math.min(4, initialGenerationThreads()));
                WRITE_EXECUTOR_CAPACITY = workers;
                WRITE_EXECUTOR = Executors.newFixedThreadPool(workers, runnable -> {
                    Thread thread = new Thread(runnable, "RW-PlannedPathWriter");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return WRITE_EXECUTOR;
        }
    }

    /** Fixed writer count, bounded by the initial-generation lane budget. */
    static int writeExecutorCapacity() {
        writeExecutor();
        return WRITE_EXECUTOR_CAPACITY;
    }

    private static int initialGenerationThreads() {
        try {
            return ThreadPoolManager.initialGenerationThreadCapacity();
        } catch (Throwable ignored) {
            return 2;
        }
    }

    private static void awaitPendingWrites(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (PENDING_WRITES.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static Optional<List<BlockPos>> find(ServerLevel level,
                                                StructureConnection connection,
                                                TerrainSamplingMode effectiveMode,
                                                PathfindingCostConfig config) {
        if (level == null || connection == null || effectiveMode == null || config == null) return Optional.empty();
        PlannedPathKey key = PlannedPathKey.of(connection);
        String computedFingerprint = fingerprintOrNull(level, effectiveMode, config);
        boolean persistent = computedFingerprint != null;
        String fingerprint = persistent ? computedFingerprint : "";
        ConcurrentHashMap<PlannedPathKey, Entry> memory = memory(level);
        Entry cached = memory.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return Optional.of(cached.path());
        }
        if (!persistent) return Optional.empty();
        try {
            Optional<List<BlockPos>> loaded = store(level).load(key, fingerprint);
            loaded.ifPresent(path -> memory.put(key, new Entry(fingerprint, path)));
            return loaded;
        } catch (IOException failure) {
            LOGGER.warn("读取待生成路径失败 dimension={} key={}", level.dimension().location(), key, failure);
            return Optional.empty();
        }
    }

    public static void discard(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) return;
        // A discarded planned path means the connection leaves the planned
        // state (generated or re-planned from scratch); its stale route-level
        // AUTO tendency must not leak into a new route on the same endpoints.
        RouteProfileTendency.clear(connection);
        PlannedPathKey key = PlannedPathKey.of(connection);
        synchronized (BY_LEVEL) {
            ConcurrentHashMap<PlannedPathKey, Entry> memory = BY_LEVEL.get(level);
            if (memory != null) memory.remove(key);
            ConcurrentHashMap<PlannedPathKey, List<ReuseRange>> reuse = REUSE_BY_LEVEL.get(level);
            if (reuse != null) reuse.remove(key);
        }
        try {
            store(level).delete(key);
        } catch (IOException failure) {
            LOGGER.warn("删除已生成路径失败 dimension={} key={}", level.dimension().location(), key, failure);
        }
    }

    public static void clear(ServerLevel level) {
        if (level == null) return;
        synchronized (BY_LEVEL) {
            BY_LEVEL.remove(level);
            REUSE_BY_LEVEL.remove(level);
        }
        synchronized (STORES) {
            STORES.remove(level);
        }
    }

    public static void clearAll() {
        // Give queued persistence writes a bounded window to finish before the
        // memory maps are dropped, so a manual cache clear cannot lose
        // already-planned routes.
        awaitPendingWrites(5, TimeUnit.SECONDS);
        RouteProfileTendency.clearAll();
        net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry.clearAll();
        synchronized (BY_LEVEL) {
            BY_LEVEL.clear();
            REUSE_BY_LEVEL.clear();
        }
        synchronized (STORES) {
            STORES.clear();
        }
    }

    /**
     * 0.8.368 (T1): how far a secondary station may sit from the primary
     * corridor and still count as sharing it. Matches the global road-snap
     * reach ({@code RoadConstants.ROAD_SNAP_THRESHOLD}) so "these two roads run
     * together" means the same thing here as it does everywhere else.
     */
    private static final int REUSE_MATCH_TOLERANCE =
            Math.max(1, net.shiroha233.roadweaver.core.constants.RoadConstants.ROAD_SNAP_THRESHOLD);
    /**
     * 0.8.369 (T1b): wider reach used only when the strict pass finds no run at
     * all. The planned path is recorded before four geometry passes and a
     * boundary trim reshape the final corridor, so a genuinely shared stretch
     * can end up further apart than the snap reach; a real save lost 5 of 9
     * ranges that way. Escalating only on a total miss cannot adopt a
     * coincidental crossing, because such a crossing still yields no run at the
     * strict reach and its geometry stays unaligned at the wider one too.
     */
    private static final int REUSE_MATCH_WIDE_TOLERANCE =
            Math.max(REUSE_MATCH_TOLERANCE, REUSE_MATCH_TOLERANCE * 3);
    /**
     * Only the same surface counts as a shared corridor; a grade-separated
     * crossing keeps its own pavement. Mirrors
     * {@code RoadSnapService.REUSE_EXTEND_MAX_HEIGHT_DELTA}.
     */
    private static final int REUSE_MATCH_MAX_HEIGHT_DELTA = 1;
    /**
     * At least this many consecutive stations must lie in the corridor before a
     * stretch is treated as shared, so a coincidental crossing is never skipped.
     * Mirrors {@code RoadSnapService.REUSE_EXTEND_MIN_RUN}.
     */
    private static final int REUSE_MATCH_MIN_RUN = 3;

    /**
     * Records that a planned secondary path must reuse an already planned corridor.
     *
     * <p>0.8.368 (T1): this used to take two {@link BlockPos} taken from the
     * <b>primary</b> path and file them under the <b>secondary</b> key. The
     * consumer then looked those coordinates up in the secondary's own segment
     * list, where they cannot exist — the two routes only share their first few
     * stations, so everything past the shared endpoint lies elsewhere. Real
     * saves showed the recorded range covering a completely disjoint area
     * (primary X -864..-816 Z 712..752 vs the secondary actually spanning
     * X -406..97 Z 119..410) and <b>11 of 11 ranges were dropped</b>, so no
     * planned corridor reuse ever reached construction.</p>
     *
     * <p>The record now stores the primary's <b>identity</b> instead. The
     * consumer reads the primary's planned path back out of {@link #BY_LEVEL}
     * (both maps share a lifetime: {@code discard}, {@code clear} and
     * {@code clearAll} remove them together) and matches geometrically against
     * the final segment list. That keeps planning's decision — which two routes
     * share a trunk — and only fixes how it is projected onto the geometry that
     * construction actually paves.</p>
     */
    public static void recordReuse(ServerLevel level, StructureConnection connection,
                                   PlannedPathKey primaryKey, List<BlockPos> primaryPath) {
        if (level == null || connection == null || primaryKey == null) return;
        PlannedPathKey key = PlannedPathKey.of(connection);
        if (key.equals(primaryKey)) return;
        if (primaryPath == null || primaryPath.isEmpty()) return;
        synchronized (BY_LEVEL) {
            REUSE_BY_LEVEL.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>())
                    .compute(key, (ignored, existing) -> {
                        List<ReuseRange> ranges = new java.util.ArrayList<>(existing == null
                                ? List.of() : existing);
                        // Deduplicate by the primary's identity, not by record
                        // equality: the record now carries a path snapshot, so
                        // equality would treat a re-recorded pair with newly
                        // sampled coordinates as a second range.
                        boolean known = false;
                        for (ReuseRange current : ranges) {
                            if (current.primaryKey().equals(primaryKey)) {
                                known = true;
                                break;
                            }
                        }
                        if (!known) ranges.add(new ReuseRange(primaryKey, primaryPath));
                        return List.copyOf(ranges);
                    });
        }
    }

    /**
     * Converts planning-time ownership into physical NETWORK_REUSE spans.
     *
     * <p>0.8.368 (T1): the ranges now name the <b>primary</b> route rather than
     * carrying its coordinates, so this walks the primary's planned path and
     * marks the secondary stations that actually lie inside that corridor. The
     * earlier exact-coordinate lookup could never succeed — {@code recordReuse}
     * stored primary coordinates under the secondary key — and it also could not
     * survive the four geometry passes in {@code Road.generateRoad} plus the
     * {@code trimPathNearStructure} prefix/suffix removal, which move and delete
     * stations.</p>
     *
     * <p>Matching is deliberately conservative: a station counts as shared only
     * when it sits within {@link #REUSE_MATCH_TOLERANCE} blocks (Chebyshev) of a
     * primary station <em>and</em> its height agrees within
     * {@link #REUSE_MATCH_MAX_HEIGHT_DELTA}, and only runs of
     * {@link #REUSE_MATCH_MIN_RUN} consecutive stations or longer become spans.
     * A coincidental crossing therefore cannot become a reused corridor, so this
     * cannot silently skip paving a stretch the planner never shared.</p>
     */
    public static List<RoadSpan> plannedReuseSpans(ServerLevel level,
                                                   StructureConnection connection,
                                                   List<RoadSegmentPlacement> segments) {
        if (level == null || connection == null || segments == null || segments.size() < 2) return List.of();
        List<ReuseRange> ranges;
        synchronized (BY_LEVEL) {
            ConcurrentHashMap<PlannedPathKey, List<ReuseRange>> reuse = REUSE_BY_LEVEL.get(level);
            ranges = reuse == null ? null : reuse.get(PlannedPathKey.of(connection));
        }
        if (ranges == null || ranges.isEmpty()) return List.of();

        List<RoadSpan> result = new java.util.ArrayList<>();
        int droppedNoPrimary = 0;
        int droppedNoOverlap = 0;
        int droppedTooShort = 0;
        for (ReuseRange range : ranges) {
            // 0.8.369 (T1b): the primary's coordinates travel with the record, so
            // the match no longer depends on that route still being registered.
            // Reading them back from BY_LEVEL failed for 4 of 9 ranges on a real
            // save, because the primary's own generation discards its entry
            // before the secondary asks.
            List<BlockPos> primaryPath = range.primaryPath();
            if (primaryPath == null || primaryPath.isEmpty()) {
                droppedNoPrimary++;
                continue;
            }
            boolean[] shared = sharedStations(primaryPath, segments);
            int runStart = -1;
            int runs = 0;
            int stations = 0;
            for (int i = 0; i <= shared.length; i++) {
                boolean inside = i < shared.length && shared[i];
                if (inside) {
                    if (runStart < 0) runStart = i;
                    stations++;
                    continue;
                }
                if (runStart >= 0 && i - runStart >= REUSE_MATCH_MIN_RUN) {
                    result.add(new RoadSpan(segments.get(runStart).middlePos(),
                            segments.get(i - 1).middlePos(), SpanType.NETWORK_REUSE));
                    runs++;
                } else if (runStart >= 0) {
                    droppedTooShort++;
                }
                runStart = -1;
            }
            if (runs == 0) droppedNoOverlap++;
            if (runs > 0) {
                LOGGER.info("RoadWeaver planned reuse matched primary={} connection={}->{} "
                                + "runs={} stations={} segments={}",
                        range.primaryKey(), connection.from(), connection.to(),
                        runs, stations, segments.size());
            }
        }
        if (droppedNoPrimary > 0 || droppedNoOverlap > 0 || droppedTooShort > 0) {
            LOGGER.info("RoadWeaver planned reuse dropped ranges={} kept={} noPrimaryPath={} "
                            + "noOverlap={} shortRuns={} segments={} connection={}->{}",
                    ranges.size(), result.size(), droppedNoPrimary, droppedNoOverlap,
                    droppedTooShort, segments.size(), connection.from(), connection.to());
        }
        return List.copyOf(result);
    }

    /**
     * Marks every secondary station that lies inside the primary's corridor.
     * A station is shared when some primary station is within
     * {@link #REUSE_MATCH_TOLERANCE} blocks horizontally and its recorded height
     * differs by at most {@link #REUSE_MATCH_MAX_HEIGHT_DELTA}; the height test
     * keeps a grade-separated crossing (underpass, flyover) from counting as a
     * shared surface corridor.
     */
    /** Package-private for {@code PlannedReuseMatchTest}. */
    static boolean[] sharedStations(List<BlockPos> primary,
                                    List<RoadSegmentPlacement> segments) {
        boolean[] shared = new boolean[segments.size()];
        if (primary == null || primary.isEmpty()) return shared;
        // 0.8.369 (T1b): match at the normal tolerance first and only fall back
        // to a wider reach when nothing matched at all. A real save produced 5
        // of 9 ranges with no overlap at the 12-block reach: the planned path is
        // recorded before four geometry passes and a boundary trim reshape the
        // final corridor, so the two can sit further apart than the snap reach.
        // Escalating only on a total miss keeps the strict test for the cases it
        // can already decide, so a coincidental crossing is still not adopted
        // while a genuinely shared corridor is no longer lost.
        int[] tolerances = {REUSE_MATCH_TOLERANCE, REUSE_MATCH_WIDE_TOLERANCE};
        matchAtTolerance(primary, segments, shared, tolerances[0]);
        if (hasRun(shared)) return shared;
        matchAtTolerance(primary, segments, shared, tolerances[1]);
        return shared;
    }

    /** True when the mask holds at least one run long enough to become a span. */
    private static boolean hasRun(boolean[] mask) {
        int run = 0;
        for (boolean value : mask) {
            run = value ? run + 1 : 0;
            if (run >= REUSE_MATCH_MIN_RUN) return true;
        }
        return false;
    }

    private static void matchAtTolerance(List<BlockPos> primary,
                                         List<RoadSegmentPlacement> segments,
                                         boolean[] shared,
                                         int tolerance) {
        java.util.Arrays.fill(shared, false);
        int cell = Math.max(1, tolerance);
        // Bucket the primary by tolerance-sized cells so the scan stays linear
        // instead of comparing every station against every primary point.
        Map<Long, List<BlockPos>> grid = new java.util.HashMap<>();
        for (BlockPos point : primary) {
            if (point == null) continue;
            grid.computeIfAbsent(cellKey(Math.floorDiv(point.getX(), cell),
                            Math.floorDiv(point.getZ(), cell)),
                    ignored -> new java.util.ArrayList<>()).add(point);
        }
        // A station within `tolerance` of a primary point can sit at most one
        // cell away in either axis, so the immediate neighbours are enough —
        // the lookup must use the same grid coordinates the buckets were built
        // from.
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment == null || segment.middlePos() == null) continue;
            BlockPos middle = segment.middlePos();
            int gx = Math.floorDiv(middle.getX(), cell);
            int gz = Math.floorDiv(middle.getZ(), cell);
            boolean found = false;
            outer:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<BlockPos> bucket = grid.get(cellKey(gx + dx, gz + dz));
                    if (bucket == null) continue;
                    for (BlockPos point : bucket) {
                        if (Math.max(Math.abs(point.getX() - middle.getX()),
                                Math.abs(point.getZ() - middle.getZ())) > tolerance) continue;
                        if (Math.abs(point.getY() - middle.getY()) > REUSE_MATCH_MAX_HEIGHT_DELTA) continue;
                        found = true;
                        break outer;
                    }
                }
            }
            shared[i] = found;
        }
    }

    /** Packs already-divided grid coordinates. Callers must pass cell indices. */
    private static long cellKey(int gridX, int gridZ) {
        return ((long) gridX << 32) ^ (gridZ & 0xffffffffL);
    }

    private static ConcurrentHashMap<PlannedPathKey, Entry> memory(ServerLevel level) {
        synchronized (BY_LEVEL) {
            return BY_LEVEL.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
        }
    }

    /**
     * 0.8.369 (T1b): names the primary route whose corridor the secondary must
     * reuse, together with a <b>snapshot of that route's planned path</b>.
     *
     * <p>0.8.368 stored only the key and read the path back from
     * {@link #BY_LEVEL} at consumption time. Real saves showed that lookup
     * failing for 4 of 9 ranges ({@code noPrimaryPath}), because the primary's
     * own route is generated — and its entry discarded — before the secondary
     * asks. Holding the coordinates here keeps the decision self-contained: it
     * is taken while both routes are live, and no later eviction can empty
     * it.</p>
     */
    public record ReuseRange(PlannedPathKey primaryKey, List<BlockPos> primaryPath) {
        public ReuseRange {
            primaryPath = primaryPath == null ? List.of() : List.copyOf(primaryPath);
        }
    }

    private static PlannedPathStore store(ServerLevel level) {
        // One store object per level: the wrapper is a stateless path holder,
        // so rebuilding it per call was pure garbage; reuse also keeps the
        // RW-PlannedPathWriter tasks allocation-light.
        synchronized (STORES) {
            PlannedPathStore cached = STORES.get(level);
            if (cached != null) return cached;
            PlannedPathStore created = new FilePlannedPathStore(
                    FileStoragePathResolver.categoryRoot(level, "planned_paths"));
            STORES.put(level, created);
            return created;
        }
    }

    private static final Map<ServerLevel, PlannedPathStore> STORES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static String fingerprintOrNull(ServerLevel level,
                                            TerrainSamplingMode effectiveMode,
                                            PathfindingCostConfig config) {
        try {
            return PlannedPathFingerprintService.create(
                    WorldgenFingerprintService.forLevel(level, PATH_WORLDGEN_SCHEMA),
                    effectiveMode,
                    config);
        } catch (RuntimeException failure) {
            LOGGER.warn("计算待生成路径指纹失败 dimension={}，仅使用内存路径",
                    level.dimension().location(), failure);
            return null;
        }
    }

    record Entry(String fingerprint, List<BlockPos> path) {}
}
