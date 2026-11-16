/* 文件职责：过滤村庄包围盒内的未知端点连接，并保留整轮撤销安全阀。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.LOGGER;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND;

/** 世界快照由调用方提供，使过滤结果和安全阀可以脱离区块加载独立验证。 */
final class VillageEndpointFilter {
    private VillageEndpointFilter() {}

    /** 仅核对已验证位点；从村庄建筑推断到的归属不能冒充已知端点。 */
    static EndpointFilterOutcome filter(List<StructureConnection> connections,
                                       List<StructureInfo> knownInfos,
                                       Function<StructureConnection, PlanningSnapshot> snapshots) {
        List<StructureConnection> kept = new ArrayList<>(connections.size());
        List<StructureConnection> filtered = new ArrayList<>();
        // 0.8.331：两端点都在同一个村庄盒内的"村内连接"（真机 village_iberian
        // (-864,752)↔(-813,716)，14 格）不算"这个村庄的路"，因此不参与安全阀二。
        // 否则阀门会把它保下来，而它必然规划失败 → 每局固定 failed=1。
        Set<StructureConnection> villageInternal = new HashSet<>();
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            PlanningSnapshot snapshot = snapshots.apply(connection);
            if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) {
                kept.add(connection);
                continue;
            }
            boolean fromUnknown = isUnknownEndpoint(connection.from(), knownInfos);
            boolean toUnknown = isUnknownEndpoint(connection.to(), knownInfos);
            StructureConnection matched = null;
            // 0.8.315：不再要求两端点距离 ≤ ENDPOINT_REPLACEMENT_MATCH_BLOCKS(24)。
            // villageOwnerAt(unknown) 已经证明未知端点落在该村庄的建筑聚合盒内；距离不提供
            // 额外信息，而大村庄的盒子动辄 60+ 格（真机 village_iberian：(-864,752)↔(-813,716)
            // 相距 62.4 格），24 格闸门会让这条过滤对大村庄永远失效。
            //
            // 0.8.317：另一端必须**确实就是那个村庄**。旧代码用 ownsEndpoint(village)，
            // 而 endpointOwners 是两个端点 owner 的并集——未知端点自己也会被
            // "从附近已核验建筑推断 owner"（真机日志 inferred endpoint owner ... (-813,716)
            // owner=village_iberian）而进入该集合。于是**别的村庄**通往这个未知点的正常路
            // （真机 (-1104,304)->(-813,716)、(-480,480)->(-813,716)，两条都是 COMPLETED）
            // 也会被判为"村庄 V ↔ V 盒内未知点"而被过滤。现在改为按已核验结构位点核对
            // 另一端点的区块与结构 id，必须与村庄 owner 完全一致。
            if (fromUnknown && !toUnknown) {
                StructureOwnerKey village = snapshot.villageOwnerAt(
                        connection.from().getX(), connection.from().getZ());
                if (village != null && endpointMatchesStructure(connection.to(), village, knownInfos)) {
                    matched = connection;
                    logFilteredVillageEndpoint(village, connection.from(), connection.to(), connection);
                    if (village.equals(snapshot.villageOwnerAt(
                            connection.to().getX(), connection.to().getZ()))) {
                        villageInternal.add(connection);
                    }
                }
            } else if (toUnknown && !fromUnknown) {
                StructureOwnerKey village = snapshot.villageOwnerAt(
                        connection.to().getX(), connection.to().getZ());
                if (village != null && endpointMatchesStructure(connection.from(), village, knownInfos)) {
                    matched = connection;
                    logFilteredVillageEndpoint(village, connection.to(), connection.from(), connection);
                    if (village.equals(snapshot.villageOwnerAt(
                            connection.from().getX(), connection.from().getZ()))) {
                        villageInternal.add(connection);
                    }
                }
            }
            if (matched == null) kept.add(connection);
            else filtered.add(matched);
        }
        if (filtered.isEmpty()) {
            return new EndpointFilterOutcome(kept, List.of(), false, "");
        }
        // 安全阀一：一轮过滤太多就整体放弃。
        if (filtered.size() > UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND) {
            LOGGER.warn("RoadWeaver route filter held back: threshold exceeded filtered={} kept={} reason=too_many_per_round",
                    filtered.size(), kept.size());
            return new EndpointFilterOutcome(connections, List.of(), true, "too_many_per_round");
        }
        // 安全阀二：过滤后某端点变成 0 连接就整体放弃。村内连接（两端同一村庄盒）
        // 不参与该判定 —— 它本来就不是"这个村庄通往别处的路"。
        if (leavesEndpointWithoutConnection(kept, filtered, villageInternal)) {
            LOGGER.warn("RoadWeaver route filter held back: threshold exceeded filtered={} kept={} reason=endpoint_left_without_connection",
                    filtered.size(), kept.size());
            return new EndpointFilterOutcome(connections, List.of(), true, "endpoint_left_without_connection");
        }
        return new EndpointFilterOutcome(kept, List.copyOf(filtered), false, "");
    }

    static void logFilteredVillageEndpoint(StructureOwnerKey village,
                                                   BlockPos unknown,
                                                   BlockPos villageEndpoint,
                                                   StructureConnection connection) {
        LOGGER.info("RoadWeaver route filtered: village {} owns endpoint, unknown endpoint inside its box "
                        + "village={} unknown={} connection={}->{}",
                village.structureId(), villageEndpoint, unknown, connection.from(), connection.to());
    }

    /**
     * 端点是否"未知"：不在已核验结构位点。
     *
     * <p>0.8.312 修正：旧实现还要求 {@code villageOwnerAt(endpoint) == null}，于是
     * "落在村庄盒内的未知端点"——正是本过滤要处理的那一类——被判成"已知"，
     * 过滤器因此永不触发（真机 0.8.311 日志 {@code filtered=0}，失败路仍在）。
     * 未知的判定只看它是不是已核验结构位点；是否落在村庄盒内由调用方单独判定。</p>
     */
    static boolean isUnknownEndpoint(BlockPos endpoint, List<StructureInfo> infos) {
        if (endpoint == null) return false;
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX() && info.pos().getZ() == endpoint.getZ()) return false;
        }
        return true;
    }

    /**
     * 0.8.317：端点是否**就是**该 owner 对应的已核验结构位点。
     *
     * <p>比 {@code snapshot.ownsEndpoint(owner)} 严格：后者是"两个端点 owner 的并集里
     * 有它就返回 true"，而未知端点自己也会因为"从附近建筑推断 owner"而进入并集，于是
     * 别的村庄通往同一个未知点的正常路会被误判。这里直接核对端点区块与结构 id。</p>
     */
    static boolean endpointMatchesStructure(BlockPos endpoint,
                                                     StructureOwnerKey owner,
                                                     List<StructureInfo> knownInfos) {
        if (endpoint == null || owner == null || knownInfos == null || knownInfos.isEmpty()) return false;
        if (ChunkPos.asLong(endpoint.getX() >> 4, endpoint.getZ() >> 4) != owner.startChunkKey()) return false;
        for (StructureInfo info : knownInfos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() != endpoint.getX() || info.pos().getZ() != endpoint.getZ()) continue;
            return owner.structureId().equals(info.structureId());
        }
        return false;
    }

    /** 过滤后是否有端点从"有连接"变成"0 连接"。 */
    static boolean leavesEndpointWithoutConnection(List<StructureConnection> kept,
                                                           List<StructureConnection> filtered,
                                                           Set<StructureConnection> exempt) {
        Map<Long, Integer> keptCounts = new HashMap<>();
        for (StructureConnection connection : kept) {
            if (connection == null) continue;
            keptCounts.merge(PlanningUtils.pos2dKey(connection.from()), 1, Integer::sum);
            keptCounts.merge(PlanningUtils.pos2dKey(connection.to()), 1, Integer::sum);
        }
        for (StructureConnection connection : filtered) {
            if (connection == null) continue;
            if (exempt != null && exempt.contains(connection)) continue;
            for (BlockPos endpoint : List.of(connection.from(), connection.to())) {
                if (endpoint == null) continue;
                if (keptCounts.getOrDefault(PlanningUtils.pos2dKey(endpoint), 0) == 0) return true;
            }
        }
        return false;
    }

}
