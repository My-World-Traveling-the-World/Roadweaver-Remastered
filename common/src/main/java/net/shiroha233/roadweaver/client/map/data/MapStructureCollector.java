package net.shiroha233.roadweaver.client.map.data;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构点快照收集。
 */
public final class MapStructureCollector {
    private MapStructureCollector() {}

    public record Result(List<BlockPos> structures, List<StructureInfo> infos, Map<BlockPos, Integer> sources) {}

    public static Result collect(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        ModConfig cfg = ConfigService.get();
        boolean allowPredicted = cfg != null
                && cfg.structurePrediction().enabled()
                && Level.OVERWORLD.equals(level.dimension());
        int[] sources = allowPredicted
                ? new int[]{StructureFileStorage.SOURCE_MANUAL, StructureFileStorage.SOURCE_PREDICTED}
                : new int[]{StructureFileStorage.SOURCE_MANUAL};

        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        List<StructureInfo> cached = new ArrayList<>();
        HashMap<Long, StructureInfo> bestInfoByPos = new HashMap<>();
        HashMap<Long, StructureInfo> identityByPos = new HashMap<>();
        HashSet<BlockPos> structuresSet = new HashSet<>();
        LinkedHashMap<BlockPos, Integer> sourcesByPos = new LinkedHashMap<>();
        for (StructureInfo info : storageSnapshot.locations().structureInfos()) {
            if (!inside(info, minBlockX, minBlockZ, maxBlockX, maxBlockZ)) continue;
            mergeBestStructureInfo(identityByPos, info);
            if (containsSource(sources, storageSnapshot.sourceAt(info.pos()))) cached.add(info);
        }
        for (StructureInfo info : cached) {
            if (info == null || info.pos() == null) continue;
            mergeBestStructureInfo(bestInfoByPos, info);
            BlockPos p = info.pos();
            BlockPos normalized = new BlockPos(p.getX(), 0, p.getZ());
            structuresSet.add(normalized);
            sourcesByPos.put(normalized, storageSnapshot.sourceAt(p));
        }
        // ── 连接端点 → 结构点（★ 2026-09-25：手绘路线的端点**不参与**）──
        //
        // 用户反馈（真机截图实证）：手绘路线 A-B-C-D-E 被拆成 4 条连接，
        // 每个连接端点都在地图上显示成 `?`（unknown 结构图标），
        // 而且因为它们沿路线分布，5 个点全在，看着很乱。
        //
        // 根因：这里对**所有**连接端点调 addConnectionEndpoint，没有对应结构时
        // 用 "unknown" 兜底（见该方法内）。而自动生成的路连的是真实村庄/建筑，
        // 端点本来就有 identity，不会变成 unknown。
        //
        // 用户要求：「只是手动画线的隐藏，自动规划的不动」
        // ⇒ 只跳过 **手绘路线** 的连接；自动生成的连接保持原行为。
        //
        // ⚠️ 为什么只改这里（不动持久化表 / 不动生成判定）：
        //   本类**只服务地图显示**（唯一调用方 MapDataCollector）。
        //   实测存档的 structures/state.json 里 unknown = 0（只有 village + spawn_cabin），
        //   说明 `?` 纯粹是渲染时临时加的，**没有污染持久化数据**。
        //   所以这里跳过它们，既让 `?` 消失，又完全不碰生成逻辑
        //   （isUnknownStructureEndpoint / 高度协商 / StructureAvoidanceService 全部照旧）——
        //   这正是用户 2026-09-25 的决定：「切方按现在的来，不动」。
        for (StructureConnection connection : MapConnectionCollector.collect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ)) {
            // ★ 2026-09-26（任务 A）：端点标记统一由开关控制（默认关）。
            //   0.8.390 这里只跳过了「手绘路线」的连接，但用户后来要求
            //   「所有的端点默认全部隐藏」—— 因为自动生成的路虽然连的是真实村庄
            //   （端点有 identity，不会变 `?`），但**连接端点本身也不该额外画点**。
            //   现在开关关着时，这里对**所有**连接都跳过端点登记；
            //   开关打开时恢复"只跳过手绘"的旧行为（便于对照）。
            if (!MapMarkersVisible.enabled() || isPlayerRouteConnection(level, connection)) {
                continue;
            }
            addConnectionEndpoint(bestInfoByPos, identityByPos, structuresSet, connection.from(), minBlockX, minBlockZ, maxBlockX, maxBlockZ);
            addConnectionEndpoint(bestInfoByPos, identityByPos, structuresSet, connection.to(), minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        }
        return new Result(new ArrayList<>(structuresSet), new ArrayList<>(bestInfoByPos.values()), sourcesByPos);
    }

    /**
     * 这条连接是否来自**玩家手绘路线**（★ 2026-09-25）。
     *
     * <h2>为什么不只用 RouteBuildRegistry</h2>
     * 注册表（{@code RouteBuildRegistry}）是**服务端运行时**表，重进世界时由
     * {@code ClientInit} 的 JOIN 处理器经 {@code server.execute(...)} **异步**重建 ——
     * 如果玩家进世界后**立刻**开地图，注册表可能还没建好，
     * 于是这些连接会被判成"非手绘" → `?` 又冒出来（只在第一次开地图时）。
     *
     * <p>所以加一层**客户端兜底**：{@code RouteEditorSession} 是客户端单例，
     * 它在 JOIN 时**同步**从 {@code routes.json}（玩家意图的权威副本）载入，
     * 不受服务端线程时序影响。</p>
     *
     * <h2>兜底怎么判</h2>
     * 手绘路线的每条连接 = 「相邻两个控制点」。所以只要连接的 from/to
     * 都能在某条路线里找到相邻的一对，就认定为手绘连接。
     * 比较用 **XZ**（Y 可能被端点协商改过，不能参与比较）。</p>
     */
    private static boolean isPlayerRouteConnection(ServerLevel level, StructureConnection connection) {
        if (connection == null) {
            return false;
        }
        // ① 主判据：服务端注册表（准确，但可能尚未重建）
        try {
            if (net.shiroha233.roadweaver.route.RouteBuildRegistry
                    .isPlayerRoute(level, connection)) {
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            // 注册表不可用（环境问题）→ 继续走兜底
        }
        // ② 兜底：客户端会话里的路线（同步载入，不受服务端线程时序影响）
        try {
            return matchesAnyRouteSegment(connection);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 连接的 (from,to) 是否与某条手绘路线的某个相邻控制点对重合（只比 XZ）。 */
    private static boolean matchesAnyRouteSegment(StructureConnection connection) {
        var session = net.shiroha233.roadweaver.route.editor.RouteEditorSession.get();
        if (session == null) {
            return false;
        }
        List<net.shiroha233.roadweaver.route.PlayerRoute> routes = session.allRoutes();
        if (routes == null || routes.isEmpty()) {
            return false;
        }
        BlockPos from = connection.from();
        BlockPos to = connection.to();
        if (from == null || to == null) {
            return false;
        }
        long fx = from.getX(), fz = from.getZ();
        long tx = to.getX(), tz = to.getZ();
        for (net.shiroha233.roadweaver.route.PlayerRoute route : routes) {
            if (route == null || route.pointCount() < 2) {
                continue;
            }
            List<net.shiroha233.roadweaver.route.RoutePoint> pts = route.points();
            for (int i = 0; i + 1 < pts.size(); i++) {
                BlockPos a = pts.get(i).pos();
                BlockPos b = pts.get(i + 1).pos();
                // 方向无关：两条连接 (A,B) 与 (B,A) 是同一个 PlannedPathKey
                boolean forward = a.getX() == fx && a.getZ() == fz
                        && b.getX() == tx && b.getZ() == tz;
                boolean backward = a.getX() == tx && a.getZ() == tz
                        && b.getX() == fx && b.getZ() == fz;
                if (forward || backward) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addConnectionEndpoint(java.util.Map<Long, StructureInfo> infos,
                                               java.util.Map<Long, StructureInfo> identities,
                                               HashSet<BlockPos> structures,
                                               BlockPos pos,
                                              int minBlockX,
                                              int minBlockZ,
                                              int maxBlockX,
                                              int maxBlockZ) {
        if (pos == null) return;
        int x = pos.getX();
        int z = pos.getZ();
        if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) return;
        BlockPos normalized = new BlockPos(x, 0, z);
        structures.add(normalized);
        long key = (((long) x) << 32) ^ (z & 0xffffffffL);
        StructureInfo identity = identities.get(key);
        mergeBestStructureInfo(infos, identity != null ? identity : new StructureInfo(normalized, "unknown"));
    }

    private static void mergeBestStructureInfo(java.util.Map<Long, StructureInfo> out, StructureInfo info) {
        if (out == null || info == null || info.pos() == null) return;
        BlockPos p = info.pos();
        int x = p.getX();
        int z = p.getZ();
        long key = (((long) x) << 32) ^ (z & 0xffffffffL);
        StructureInfo prev = out.get(key);
        if (prev == null) {
            out.put(key, new StructureInfo(new BlockPos(x, 0, z), info.structureId()));
            return;
        }
        String prevId = prev.structureId();
        String nextId = info.structureId();
        if (!StructureInfo.isKnownId(prevId) && StructureInfo.isKnownId(nextId)) {
            out.put(key, new StructureInfo(new BlockPos(x, 0, z), nextId));
        }
    }

    private static boolean inside(StructureInfo info, int minX, int minZ, int maxX, int maxZ) {
        if (info == null || info.pos() == null) return false;
        int x = info.pos().getX();
        int z = info.pos().getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static boolean containsSource(int[] sources, int source) {
        if (sources == null || sources.length == 0) return true;
        for (int candidate : sources) {
            if (candidate == source) return true;
        }
        return false;
    }
}
