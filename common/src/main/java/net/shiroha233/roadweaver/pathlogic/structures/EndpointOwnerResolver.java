/* 文件职责：结构端点的归属推断 —— 判断哪个结构「属于」这条路的两端。 */
package net.shiroha233.roadweaver.pathlogic.structures;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;

// 仍留在避让服务里的常量与工具（包内可见），静态导入后方法体不用改 —— 纯搬迁。
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.*;

/**
 * 结构端点归属推断。
 *
 * <h2>它解决什么问题</h2>
 * <p>一条路的起点/终点常常就落在某个村庄或要塞里。这些结构是<b>它的端点</b>，
 * 不该当成挡路障碍把自己拦下来 —— 否则路永远连不上村子。但反过来，
 * 路边的<b>另一个</b>同类型结构必须照常避让。所以需要一把精确的「归属」判据。</p>
 *
 * <h2>三道依据（越靠后越弱）</h2>
 * <ol>
 *   <li><b>结构信息</b>：连接上直接带了结构 id 与坐标，最可靠；</li>
 *   <li><b>端点重合</b>：端点正好落在某个已知结构的包围盒里；</li>
 *   <li><b>邻近推断</b>：附近只有一个候选且距离够近 —— <b>并列即放弃</b>，
 *       宁可判不出也不误判（误判会让本该避让的结构被放进路里）。</li>
 * </ol>
 *
 * <p>本类是从 2790 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>出来的，
 * 逻辑未改（含缩进）。为让两侧调用点零改动用了双向静态导入。</p>
 */
public final class EndpointOwnerResolver {

    /**

     * 端点归属四段解析全失败的累计次数（2026-11-15 新增，纯取证）。

     *

     * <p>四段依次是：①精确 identity（XZ 匹配）②盒内唯一同 id ③同名族替换（ctov 之类改名村庄）

     * ④附近已验证建筑推断。全失败时<b>一个 owner 键都不会加</b>，

     * 意味着这个端点所在的结构不再享受豁免 —— 而它与「端点本来就在结构外面」完全同形。</p>

     */

    private static final java.util.concurrent.atomic.AtomicLong UNRESOLVED_ENDPOINT_OWNERS =

            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicBoolean UNRESOLVED_WARNED =

            new java.util.concurrent.atomic.AtomicBoolean(false);


    static Set<StructureOwnerKey> endpointOwnersForRoad(ServerLevel level, RoadData road) {
        if (level == null || road == null || !road.hasOwnerPair()) return Set.of();
        RouteKey key = routeKey(level, road);
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
        if (snapshot != null && snapshot != PlanningSnapshot.EMPTY) {
            StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.put(key, snapshot.endpointOwners());
            return snapshot.endpointOwners();
        }
        return StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.computeIfAbsent(key, ignored -> {
            BlockPos from = PlanningUtils.posFrom2dKey(road.ownerA2dKey());
            BlockPos to = PlanningUtils.posFrom2dKey(road.ownerB2dKey());
            StructureConnection connection = new StructureConnection(from, to);
            return resolveEndpointOwners(
                    connection, StructureFileStorage.getStructureSnapshot(level), Set.of());
        });
    }

    static Set<StructureOwnerKey> resolveEndpointOwners(
            StructureConnection connection,
            StructureFileStorage.StructureSnapshot storageSnapshot,
            Collection<OwnedStructureBox> boxes) {
        if (connection == null) return Set.of();
        List<StructureInfo> infos = storageSnapshot == null || storageSnapshot.locations() == null
                ? List.of()
                : storageSnapshot.locations().structureInfos();
        return resolveEndpointOwnersFromInfos(connection, infos, boxes);
    }

    static Set<StructureOwnerKey> resolveEndpointOwnersForTest(
            StructureConnection connection,
            List<StructureInfo> infos,
            Collection<OwnedStructureBox> boxes) {
        return resolveEndpointOwnersFromInfos(connection, infos, boxes);
    }

    /**
     * True when the endpoint is a real village intersection. Only these use the
     * precise crossing terrain height. Spawn and non-village endpoints keep the
     * structure bounding-box height so the road climbs to the structure instead
     * of dipping to a lower crossing column (which broke the connection).
     */
    /** True when this endpoint is a known (named/village) structure location, regardless of snapshot timing. */
    public static boolean isKnownStructureEndpoint(ServerLevel level, StructureConnection connection, boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        StructureFileStorage.StructureSnapshot snapshot = StructureFileStorage.getStructureSnapshot(level);
        if (snapshot == null || snapshot.locations() == null) return false;
        for (StructureInfo info : snapshot.locations().structureInfos()) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX() && info.pos().getZ() == endpoint.getZ()) return true;
        }
        return false;
    }

    public static boolean isVillageEndpoint(ServerLevel level, StructureConnection connection, boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null && spawn.getX() == endpoint.getX() && spawn.getZ() == endpoint.getZ()) {
            return false;
        }
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY
                || snapshot.boxes() == null || snapshot.boxes().isEmpty()) return false;
        long maxDistanceSquared = (long) ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS
                * ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
        // 原来是遍历 snapshot.boxes() 全表 —— 每次端点推断都要扫一遍所有结构盒。
        // 快照自带「区块 → 盒」桶表，这里零拷贝复用成空间索引：只取端点周围
        // ±ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS 覆盖的桶。
        // 3x3 桶 = 每轴 ±16..31 格，必然覆盖「距离 ≤ 16」的全部盒 ⇒ 结果是超集，
        // 下面的判据一字未改，所以返回值与全表扫描逐位相同。
        int reach = ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
        List<OwnedStructureBox> candidates = StructureBoxIndex
                .fromChunkBuckets(snapshot.boxesByChunk())
                .query(endpoint.getX() - reach, endpoint.getZ() - reach,
                        endpoint.getX() + reach, endpoint.getZ() + reach);
        for (OwnedStructureBox owned : candidates) {
            if (owned == null || owned.box() == null || owned.owner() == null) continue;
            if (!isVillageStructure(owned.owner().structureId())) continue;
            if (horizontalDistanceSquared(owned.box(), endpoint) <= maxDistanceSquared) return true;
        }
        return false;
    }

    static Set<StructureOwnerKey> resolveEndpointOwnersFromInfos(
            StructureConnection connection,
            List<StructureInfo> infos,
            Collection<OwnedStructureBox> boxes) {
        if (connection == null) return Set.of();
        Set<StructureOwnerKey> result = new HashSet<>();
        if (infos == null || infos.isEmpty()) {
            resolveEndpointOwner(connection.from(), List.of(), boxes, result);
            resolveEndpointOwner(connection.to(), List.of(), boxes, result);
            return result.isEmpty() ? Set.of() : Set.copyOf(result);
        }
        resolveEndpointOwner(connection.from(), infos, boxes, result);
        resolveEndpointOwner(connection.to(), infos, boxes, result);
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    static void resolveEndpointOwner(BlockPos endpoint,
                                             List<StructureInfo> infos,
                                             Collection<OwnedStructureBox> boxes,
                                             Set<StructureOwnerKey> result) {
        if (endpoint == null) return;
        StructureInfo identity = null;
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX() && info.pos().getZ() == endpoint.getZ()) {
                identity = info;
                break;
            }
        }
        if (identity == null) {
            InferredEndpointOwner inferred = inferNearbyEndpointOwner(endpoint, boxes);
            if (inferred != null) {
                result.add(inferred.owner());
                LOGGER.info("【从附近已确认建筑推断端点归属】 " + "RoadWeaver inferred endpoint owner from nearby verified building endpoint={} owner={} distance={}",
                        endpoint, inferred.owner(), Math.sqrt(inferred.distanceSquared()));
                return;
            }
            // 2026-11-15：**这是唯一的零 owner 出口** —— 精确 identity 没找到，
            // 附近推断也失败，于是这个端点拿不到任何 owner 键。
            // 后果：端点所在结构不再享受豁免，路可能被自己的目的地建筑挡回来；
            // 而它与「端点本来就在结构外面」完全同形，原来日志里一个字都没有。
            // 注意：identity 找到时会无条件加 expected（见下面的 result.add），
            // 所以那条路径不可能零 owner —— 不要把计数搬到方法末尾，那里不可达。
            noteUnresolvedEndpointOwner(endpoint, null);
            return;
        }
        StructureOwnerKey expected = new StructureOwnerKey(
                ChunkPos.asLong(identity.pos().getX() >> 4, identity.pos().getZ() >> 4),
                identity.structureId());
        // A selected endpoint is allowed to receive its own road.  The owner
        // key is still exact (structure start chunk plus registry id), so a
        // nearby instance of the same type remains protected.
        result.add(expected);
        if (boxes == null || boxes.isEmpty()) {
            return;
        }
        for (OwnedStructureBox owned : boxes) {
            if (owned != null && expected.equals(owned.owner())) {
                result.add(expected);
                return;
            }
        }

        Map<StructureOwnerKey, BoundingBox> matching = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.owner() == null || owned.box() == null) continue;
            matching.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        StructureOwnerKey unique = null;
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : matching.entrySet()) {
            if (!identity.structureId().equals(entry.getKey().structureId())) continue;
            if (!containsHorizontal(entry.getValue(), endpoint)) continue;
            if (unique != null && !unique.equals(entry.getKey())) return;
            unique = entry.getKey();
        }
        if (unique != null) {
            result.add(unique);
            return;
        }

        // Some worldgen packs replace a vanilla predicted village with a
        // namespaced variant (for example minecraft:village_taiga becomes
        // ctov:small/village_taiga).  The selected coordinate is still the
        // same logical endpoint, but the registry id and start chunk identity
        // differ. Match only a unique, nearby start with the same terminal
        // family name; unrelated structures remain protected.
        String family = endpointStructureFamily(identity.structureId());
        if (family.isEmpty()) return;
        StructureOwnerKey replacement = null;
        long replacementDistance = Long.MAX_VALUE;
        boolean ambiguous = false;
        long maximumDistanceSquared = (long) ENDPOINT_REPLACEMENT_MATCH_BLOCKS
                * ENDPOINT_REPLACEMENT_MATCH_BLOCKS;
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : matching.entrySet()) {
            StructureOwnerKey owner = entry.getKey();
            if (owner == null || !family.equals(endpointStructureFamily(owner.structureId()))) continue;
            long distance = horizontalDistanceSquared(entry.getValue(), endpoint);
            if (distance > maximumDistanceSquared) continue;
            if (distance < replacementDistance) {
                replacement = owner;
                replacementDistance = distance;
                ambiguous = false;
            } else if (distance == replacementDistance && replacement != null && !replacement.equals(owner)) {
                ambiguous = true;
            }
        }
        if (replacement != null && !ambiguous) {
            result.add(replacement);
            if (ENDPOINT_OWNER_LOGGED.add(endpoint.toString())) {
                LOGGER.info("【端点登记归属与实际建筑不一致，已重新映射】 " + "RoadWeaver remapped selected endpoint owner endpoint={} registered={} actual={} distance={}",
                        endpoint, identity.structureId(), replacement.structureId(), Math.sqrt(replacementDistance));
            }
            return;
        }

        // A number of worldgen packs replace a predicted village start with a
        // differently named template structure. The generated building is
        // still the selected endpoint when it is the only verified building
        // immediately beside that endpoint. Treat it as the road exit only;
        // buildings farther away and ambiguous neighbours remain protected.
        InferredEndpointOwner inferred = inferNearbyEndpointOwner(endpoint, boxes);
        if (inferred != null) {
            result.add(inferred.owner());
            if (ENDPOINT_OWNER_LOGGED.add(endpoint.toString())) {
                LOGGER.info("【已推断端点出口所属建筑】 " + "RoadWeaver inferred endpoint exit owner endpoint={} registered={} actual={} distance={}",
                        endpoint, identity.structureId(), inferred.owner().structureId(),
                        Math.sqrt(inferred.distanceSquared()));
            }
            return;
        }
    }

    /**
     * 记一次「端点四段归属解析全失败」（2026-11-15 新增，纯取证）。
     *
     * <p>只在首次打 warning（{@code compareAndSet} 保证），之后只累加计数 ——
     * 解析发生在每次规划、每条路的两端，逐次打日志会把 latest.log 冲爆。</p>
     */
    private static void noteUnresolvedEndpointOwner(BlockPos endpoint, String registeredId) {
        long n = UNRESOLVED_ENDPOINT_OWNERS.incrementAndGet();
        if (UNRESOLVED_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("【端点归属无法解析，目的建筑不再豁免避让；优先检查归属登记】 " + "RoadWeaver could not resolve any owner for endpoint {} (registered={})"
                            + " — this endpoint's structure loses its exemption, so the road may be"
                            + " blocked by its own destination building. First of possibly many (count={})",
                    endpoint, registeredId, n);
        }
    }

    /** 「端点归属四段解析全失败」的累计次数（只读取证）。 */
    public static long unresolvedEndpointOwnerCount() {
        return UNRESOLVED_ENDPOINT_OWNERS.get();
    }

    /** 重置取证计数（测试用）。 */
    static void resetUnresolvedEndpointOwnerCount() {
        UNRESOLVED_ENDPOINT_OWNERS.set(0);
        UNRESOLVED_WARNED.set(false);
    }

    static InferredEndpointOwner inferNearbyEndpointOwner(
            BlockPos endpoint, Collection<OwnedStructureBox> boxes) {
        if (endpoint == null || boxes == null || boxes.isEmpty()) return null;
        Map<StructureOwnerKey, BoundingBox> merged = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (!isVerifiedBuilding(owned) || owned.owner() == null || owned.box() == null) continue;
            if (!isVillageStructure(owned.owner().structureId())) continue;
            merged.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        long maximumDistanceSquared = (long) ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS
                * ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
        StructureOwnerKey nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        boolean ambiguous = false;
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : merged.entrySet()) {
            long distance = horizontalDistanceSquared(entry.getValue(), endpoint);
            if (distance > maximumDistanceSquared) continue;
            if (distance < nearestDistance) {
                nearest = entry.getKey();
                nearestDistance = distance;
                ambiguous = false;
            } else if (distance == nearestDistance && nearest != null
                    && !nearest.equals(entry.getKey())) {
                ambiguous = true;
            }
        }
        return nearest == null || ambiguous
                ? null
                : new InferredEndpointOwner(nearest, nearestDistance);
    }

    record InferredEndpointOwner(StructureOwnerKey owner, long distanceSquared) {
    }

    static StructureOwnerKey levelOwner(ServerLevel level, Structure structure, long startChunkKey) {
        String structureId = "unknown";
        if (level != null && structure != null) {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var key = registry.getKey(structure);
            if (key != null) structureId = key.toString();
        }
        return new StructureOwnerKey(startChunkKey, structureId);
    }
}
