/* 文件职责：截断与既有路重叠的规划段，避免沿同一条走廊重复开路。 */
package net.shiroha233.roadweaver.planning;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规划完成后的「多路汇聚同一村庄」合并（0.9.084，判据重写）。
 *
 * <h2>要解决的问题</h2>
 * <p>多条连接汇聚到</b>同一个村庄</b>时，A\* 是各自独立跑的，于是它们在村口附近
 * 走成几条来回交叉的路 —— 地图上就是一片乱网。真机取证（存档 {@code 20261111 1704}）：
 * 三条连接汇聚到 {@code (1936,-400)}，其中 1570 站与 2441 站那两条的足迹范围
 * 几乎完全重叠，但<b>几何上真正重合的站只有 9 个</b>。</p>
 *
 * <h2>为什么 0.9.083 的判据不成立（已废弃）</h2>
 * <p>0.9.083 用 {@code RoadBraidDetector}（{@code MAX_DISTANCE = 12} 格 +
 * {@code MIN_RUN = 6} 站连续）找「末尾并行」，可那套判据是为
 * <b>平行双桥</b>设计的 —— 它要求两条路真的贴在一起走。而村庄汇聚的形态是
 * 「各自绕进同一个村」，几何上根本不贴。实测 {@code planReplacements} 返回空，
 * 机制在真机上<b>一次都没触发</b>。</p>
 *
 * <h2>现在的判据：只看拓扑，不看几何</h2>
 * <p>端点度数分布（本局 20 条路）：度 1 = 11 个端点、度 2 = 8 个、度 3 = 3 个、度 4 = 1 个 ——
 * 也就是 12 个村庄接到了 2~4 条路。所以判据就是：</p>
 * <ol>
 *   <li>同一村庄端点上有 ≥ 2 条连接；</li>
 *   <li>按路径长度降序，最长的那条成为该端点的主干；</li>
 *   <li>其余每条把该端点改到「主干上离村庄 {@value #MERGE_DISTANCE_FROM_VILLAGE} 格的那个站」。</li>
 * </ol>
 * <p>改完再 {@link PlannedPathCache#discard} 掉旧路径，生成期按新端点重算 ——
 * 全程不碰已生成的方块，也不用重跑整轮规划。</p>
 *
 * <h2>这个判据天然覆盖的形态</h2>
 * <ul>
 *   <li><b>两条路去同一村庄</b>：短的接进长的，村庄只留一条进村。</li>
 *   <li><b>两条路从同一村庄出发</b>：同上，只是截的是起点。</li>
 *   <li><b>三条/四条路汇聚</b>：除主干外全部接进主干。</li>
 *   <li><b>A、B 先交叉、C 又进来</b>：它们共享的是端点，同一簇里一起处理。</li>
 * </ul>
 *
 * <h2>明确不处理（写在这里防以后误加）</h2>
 * <ul>
 *   <li><b>两条路只是中途交叉、各自去不同村庄</b>：那是正常路口，不是冗余。
 *       判据看端点，所以这种情况天然不会被碰 —— 这正是旧判据最容易犯错的地方。</li>
 *   <li><b>两条路中途交叉后继续并行一段</b>：属于「路段冗余」，要改路径中间段、
 *       重算纵断面，成本远高于端点合并。真机 {@code braidPairs=7} 里绝大多数是
 *       {@code primary-range-short}（并行段太短），暂不做。</li>
 * </ul>
 */
public final class RoadOverlapTruncator {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /**
     * 从村庄退多远合并（格）。支路在这个距离上并入主干，之后只留主干进村。
     *
     * <p>64 格的取法：村庄保护包围盒通常在 32~48 格量级，提前到 64 格合并
     * 能保证枢纽落在保护范围外，不会把交汇点塞进村里；再远就会让支路变得过短。</p>
     */
    private static final int MERGE_DISTANCE_FROM_VILLAGE = 64;

    /** 截断后这条路剩下的长度下限（站）。低于它就不截，否则变成碎路。 */
    private static final int MIN_KEPT_STATIONS = 16;

    private RoadOverlapTruncator() {
    }

    /** 输出旧连接到新连接的替换映射，空结果明确表示没有合并。
 *
     * @return 旧连接 → 新连接 的替换映射（没有可合并的时候返回空 Map）
     */
    public static Map<StructureConnection, StructureConnection> findReplacements(
            ServerLevel level, List<StructureConnection> connections) {
        Map<StructureConnection, StructureConnection> empty = Map.of();
        if (level == null || connections == null || connections.size() < 2) return empty;

        ModConfig config;
        try {
            config = ConfigService.get();
        } catch (Throwable couldNotRead) {
            return empty;
        }
        if (config == null) return empty;

        // 0.9.086：路径缓存按 fingerprint 匹配，而 fingerprint 里含 AStarStep。
        //
        // 真机取证（0.9.085 那局，存档 20:07）：village merge 恰好 0 次 ——
        // planRoadTerrain 注册路径时用的是 config.pathfindingCost().withPathGridStep(step)
        // （RoadPlanningService:696/:718），而这里原先用不带 step 的 config，
        // fingerprint 永远对不上 ⇒ 每条连接都 find 不到路径 ⇒ 机制静默失效。
        //
        // 规划期会先后以「配置 step / 4 / 2」三种步长注册过路径（精度档位），
        // 所以这里按同样的候选集逐个试，命中即用。
        TerrainSamplingMode mode = TerrainSamplingSessions.forLevel(level).effectiveMode();
        int[] stepCandidates = stepCandidates(config);
        Map<StructureConnection, List<BlockPos>> pathByConnection = new LinkedHashMap<>();
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            List<BlockPos> path = findPlannedPath(level, connection, mode, config, stepCandidates);
            if (path != null && path.size() >= 2) {
                pathByConnection.put(connection, path);
            }
        }
        if (pathByConnection.size() < 2) {
            LOGGER.info("【可用规划路径不足，跳过村庄路网合并】 " + "RoadWeaver village merge skipped: only {} connection(s) had a planned path "
                    + "(out of {}); stepCandidates={}", pathByConnection.size(), connections.size(),
                    java.util.Arrays.toString(stepCandidates));
            return empty;
        }

        Map<StructureConnection, StructureConnection> replaced = planReplacements(pathByConnection);
        if (replaced.isEmpty()) return empty;

        for (StructureConnection old : replaced.keySet()) {
            try {
                PlannedPathCache.discard(level, old);
            } catch (Throwable couldNotDiscard) {
                LOGGER.warn("【合并后的旧规划路径清理失败】 " + "RoadWeaver could not discard merged path for {} -> {}", old.from(), old.to());
            }
        }
        LOGGER.info("【村庄路网合并数量】 " + "RoadWeaver village merge: connections={} merged={}", connections.size(), replaced.size());
        return replaced;
    }

    /** 规划期可能用过的 AStarStep：配置值 + 两个精度档位。 */
    private static int[] stepCandidates(ModConfig config) {
        int configured = 8;
        try {
            configured = config.pathfindingCost().pathGridStep();
        } catch (Throwable ignored) {
            // 读不到就用默认 8
        }
        int safe = Math.max(1, configured);
        return safe == 8 ? new int[]{8, 4, 2} : new int[]{safe, 8, 4, 2};
    }

    /** 按候选 step 逐个查规划路径，命中即返回。 */
    private static List<BlockPos> findPlannedPath(ServerLevel level, StructureConnection connection,
                                                  TerrainSamplingMode mode,
                                                  ModConfig config, int[] stepCandidates) {
        for (int step : stepCandidates) {
            try {
                Optional<List<BlockPos>> path = PlannedPathCache.find(level, connection, mode,
                        config.pathfindingCost().withPathGridStep(step));
                if (path.isPresent() && path.get().size() >= 2) {
                    return path.get();
                }
            } catch (Throwable couldNotRead) {
                // 继续试下一个 step
            }
        }
        return null;
    }

    /**
     * <b>决策主体（纯函数）</b>：给定「连接 → 规划路径」，返回 旧连接 → 新连接 的替换映射。
     *
     * <p>不访问世界、不访问缓存、不改任何静态状态。判据是<b>端点拓扑</b>：
     * 同一村庄端点上有几条连接、谁最长。与几何完全无关，所以真机那种
     * 「各自绕进同一个村、几何上不重合」的形态也能稳定命中。</p>
     *
     * <p>顺序契约：按路径长度<b>降序</b>处理。长的先占住它两端的端点成为主干；
     * 后来者若发现自己落在某个已有主干覆盖的端点上，就改接到那条主干。</p>
     */
    public static Map<StructureConnection, StructureConnection> planReplacements(
            Map<StructureConnection, List<BlockPos>> pathByConnection) {
        if (pathByConnection == null || pathByConnection.size() < 2) return Map.of();

        List<Map.Entry<StructureConnection, List<BlockPos>>> ordered = new ArrayList<>(pathByConnection.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        // 端点 → 已经占住它的主干连接
        Map<Column, StructureConnection> trunkByEndpoint = new HashMap<>();
        // 端点 → 该端点上的总连接数（用来挑「更该截」的那一端）
        Map<Column, Integer> degreeByEndpoint = new HashMap<>();
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : ordered) {
            increment(degreeByEndpoint, columnOf(entry.getKey().from()));
            increment(degreeByEndpoint, columnOf(entry.getKey().to()));
        }

        Map<StructureConnection, StructureConnection> replaced = new LinkedHashMap<>();

        for (Map.Entry<StructureConnection, List<BlockPos>> entry : ordered) {
            StructureConnection connection = entry.getKey();
            List<BlockPos> path = entry.getValue();
            Column fromColumn = columnOf(connection.from());
            Column toColumn = columnOf(connection.to());
            if (fromColumn == null || toColumn == null) continue;

            StructureConnection trunkAtFrom = trunkByEndpoint.get(fromColumn);
            StructureConnection trunkAtTo = trunkByEndpoint.get(toColumn);

            // 两端都没有更长的路占着 —— 我成为这两端的主干
            if (trunkAtFrom == null && trunkAtTo == null) {
                trunkByEndpoint.put(fromColumn, connection);
                trunkByEndpoint.put(toColumn, connection);
                continue;
            }

            // 只挑一端接过去：优先度数高的（那里更拥挤），度数相同则优先目标端
            boolean mergeFrom = shouldMergeAtFrom(fromColumn, toColumn, trunkAtFrom, trunkAtTo, degreeByEndpoint);
            StructureConnection trunk = mergeFrom ? trunkAtFrom : trunkAtTo;
            Column anchor = mergeFrom ? fromColumn : toColumn;
            if (trunk == null) {
                // 选定的一端没有主干，退到另一端
                mergeFrom = !mergeFrom;
                trunk = mergeFrom ? trunkAtFrom : trunkAtTo;
                anchor = mergeFrom ? fromColumn : toColumn;
                if (trunk == null) continue;
            }

            List<BlockPos> trunkPath = pathByConnection.get(trunk);
            if (trunkPath == null || trunkPath.size() < 2) {
                trunkByEndpoint.putIfAbsent(anchor, connection);
                continue;
            }

            BlockPos mergePoint = pointAtDistanceFrom(trunkPath, anchor, MERGE_DISTANCE_FROM_VILLAGE);
            if (mergePoint == null) {
                // 主干太短，退不到村庄外 64 格 —— 不动它
                trunkByEndpoint.putIfAbsent(anchor, connection);
                continue;
            }

            if (mergeFrom) {
                if (path.size() < MIN_KEPT_STATIONS) continue;
                if (sameColumn(mergePoint, connection.from())) continue;
                StructureConnection merged = new StructureConnection(mergePoint, connection.to());
                replaced.put(connection, merged);
                LOGGER.info("【村庄路网已接入共用主干】 " + "RoadWeaver village merge: {} -> {} becomes {} -> {} "
                                + "(mergedAt={} village={} trunk={} -> {} trunkStations={})",
                        connection.from(), connection.to(), merged.from(), merged.to(),
                        mergePoint, connection.from(), trunk.from(), trunk.to(), trunkPath.size());
            } else {
                if (path.size() < MIN_KEPT_STATIONS) continue;
                if (sameColumn(mergePoint, connection.to())) continue;
                StructureConnection merged = new StructureConnection(connection.from(), mergePoint);
                replaced.put(connection, merged);
                LOGGER.info("【村庄路网已接入共用主干】 " + "RoadWeaver village merge: {} -> {} becomes {} -> {} "
                                + "(mergedAt={} village={} trunk={} -> {} trunkStations={})",
                        connection.from(), connection.to(), merged.from(), merged.to(),
                        mergePoint, connection.to(), trunk.from(), trunk.to(), trunkPath.size());
            }
        }
        return replaced;
    }

    /** 一端有主干、另一端没有时选有主干的那端；两端都有则选度数更高的（更拥挤）。 */
    private static boolean shouldMergeAtFrom(Column fromColumn, Column toColumn,
                                             StructureConnection trunkAtFrom, StructureConnection trunkAtTo,
                                             Map<Column, Integer> degreeByEndpoint) {
        if (trunkAtFrom != null && trunkAtTo == null) return true;
        if (trunkAtFrom == null) return false;
        int fromDegree = degreeByEndpoint.getOrDefault(fromColumn, 0);
        int toDegree = degreeByEndpoint.getOrDefault(toColumn, 0);
        return fromDegree >= toDegree;
    }

    /**
     * 沿主干路径从 {@code village} 那一端往里走 {@code distance} 格，返回落点站。
     *
     * <p>按<b>累计水平距离</b>走，不按站索引 —— 站点间距不均匀（真机实测从
     * 0.4 到 8 格/站都有），按索引走会在密站路段上退得太近。</p>
     *
     * @return 落点；主干总长不足 {@code distance} 或端点对不上时返回 {@code null}
     */
    static BlockPos pointAtDistanceFrom(List<BlockPos> path, Column village, int distance) {
        if (path == null || path.size() < 2 || village == null) return null;
        BlockPos head = path.get(0);
        BlockPos tail = path.get(path.size() - 1);
        boolean fromHead;
        if (sameColumn(head, village)) {
            fromHead = true;
        } else if (sameColumn(tail, village)) {
            fromHead = false;
        } else {
            return null;
        }

        double travelled = 0.0D;
        int index = fromHead ? 0 : path.size() - 1;
        int step = fromHead ? 1 : -1;
        while (true) {
            int next = index + step;
            if (next < 0 || next >= path.size()) return null;
            BlockPos current = path.get(index);
            BlockPos following = path.get(next);
            travelled += Math.max(1, horizontalDistance(current, following));
            index = next;
            if (travelled >= distance) return path.get(index);
        }
    }

    private static int horizontalDistance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    private static void increment(Map<Column, Integer> map, Column column) {
        if (column != null) map.merge(column, 1, Integer::sum);
    }

    /** 端点比较只看 XZ —— 村庄端点的 Y 在不同批次里可能是 0 或真实地表高度。 */
    private static Column columnOf(BlockPos pos) {
        return pos == null ? null : new Column(pos.getX(), pos.getZ());
    }

    private static boolean sameColumn(BlockPos pos, Column column) {
        return pos != null && column != null && pos.getX() == column.x() && pos.getZ() == column.z();
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a != null && b != null && a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    /** XZ 列坐标，用作端点 map 的 key。 */
    record Column(int x, int z) {
    }
}
