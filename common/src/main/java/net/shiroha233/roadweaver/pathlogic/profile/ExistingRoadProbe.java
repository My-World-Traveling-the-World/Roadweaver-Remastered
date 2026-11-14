/* 文件职责：已有公路探针 —— 回答「这个位置是否被别的路占用」，并排除当前路自身。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.chunk.RoadFootprint;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;

/**
 * 已有公路探针（2026-11-15 新增，补齐 {@link GroundBanSources} 的第三条来源）。
 *
 * <h2>它解决什么</h2>
 * <p>{@link GroundBanSources} 的禁地面掩码需要「这个位置已经有路了」这条物理事实 ——
 * 有路的列当然不能再铺一遍地面。但直接查「附近有没有路」会踩一个致命的自污染陷阱。</p>
 *
 * <h2>★ 自污染陷阱（为什么必须排除自己）</h2>
 * <p>剖面计算发生在<b>落盘之前</b>，所以首次规划时存储里没有当前路，查不到自己，安全。
 * 但存在重规划路径：</p>
 * <pre>
 *   ParallelOverlapResolver.rerouteAvoiding
 *     → RoadGenerationService.regenerateRoadWithoutPersist
 *       → Road.generateRoad(..., persist=false)
 *         → 剖面计算                     ← 此时当前路的旧几何已经在存储里
 * </pre>
 * <p>一旦对已落盘的路重算剖面，整条走廊都会被它自己的旧几何判成「已有公路」
 * ⇒ 全站禁地面 ⇒ DP 只能全程架桥/挖隧道，轻则日志失真重则不可解。</p>
 *
 * <p>所以本类在构建时<b>按端点 owner 键排除当前连接</b>：
 * {@code Road.java:115-116} 在调用剖面计算之前就已经算好了
 * {@code ownerA2d}/{@code ownerB2d}，把它传进来即可精确识别「哪条是自己」。</p>
 *
 * <h2>性能</h2>
 * <p>不做逐站查询。构建时用一次 {@link RoadShardStorage#queryRect} 取走廊矩形内的
 * 全部道路，过滤掉自己，再把每条路的 {@link RoadFootprint} 建<b>一次</b>
 * （{@code RoadFootprint.from} 是 O(站数)，绝不能逐站重建）。
 * 之后逐站查询只是 {@code Map} 取位图 + 位运算。</p>
 *
 * <p>另：{@code RoadSnapshot.hasRoadInRect} 里的 footprint 也是预先建好缓存的
 * （{@code entry.footprint()}），所以走 footprint 而不是重新 {@code from()} 是对的。</p>
 */
public final class ExistingRoadProbe {

    /** 没有任何其它道路时的常量探针，避免到处判空。 */
    private static final GroundBanSources.RoadProbe NONE = (x, z) -> false;

    private ExistingRoadProbe() {
    }

    /**
     * 为一条走廊建立探针。
     *
     * @param level  要查询的世界（非主世界会返回空探针）
     * @param centers 当前路的中心线站点；为空时返回空探针
     * @param ownerA2dKey 当前路的端点 A 二维键（{@code Road.java:115}）
     * @param ownerB2dKey 当前路的端点 B 二维键（{@code Road.java:116}）
     * @return 逐站查询用的探针；任何异常或退化输入都返回「查不到」的空探针
     */
    public static GroundBanSources.RoadProbe forCorridor(ServerLevel level,
                                                         List<BlockPos> centers,
                                                         long ownerA2dKey,
                                                         long ownerB2dKey) {
        if (level == null || centers == null || centers.isEmpty()) {
            return NONE;
        }
        try {
            // ── 走廊包围盒 ──
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos center : centers) {
                if (center == null) continue;
                minX = Math.min(minX, center.getX());
                minZ = Math.min(minZ, center.getZ());
                maxX = Math.max(maxX, center.getX());
                maxZ = Math.max(maxZ, center.getZ());
            }
            if (minX > maxX) return NONE;

            List<RoadData> existing = RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);
            if (existing == null || existing.isEmpty()) return NONE;

            // ── 过滤自身 + 一次建表 ──
            List<RoadFootprint> footprints = new ArrayList<>(existing.size());
            for (RoadData road : existing) {
                if (road == null || isSelf(road, ownerA2dKey, ownerB2dKey)) continue;
                footprints.add(RoadFootprint.from(road));
            }
            if (footprints.isEmpty()) return NONE;

            // ── 逐站查询只是一次 Map 取位图 + 位运算 ──
            return (x, z) -> {
                long packedChunk = net.shiroha233.roadweaver.persistence.chunk.RoadChunkKey.pack(
                        Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                int localX = Math.floorMod(x, 16);
                int localZ = Math.floorMod(z, 16);
                for (RoadFootprint footprint : footprints) {
                    RoadFootprint.ChunkMask mask = footprint.mask(packedChunk);
                    if (mask != null && mask.containsLocal(localX, localZ)) {
                        return true;
                    }
                }
                return false;
            };
        } catch (Throwable ignored) {
            // 探针是观测/约束的辅助信息，绝不允许因为存储未就绪而打断规划。
            // 拿不到就当「没有别的路」—— 保守方向是「不禁地面」，
            // 因为误禁会让整条路无法铺地面，比漏禁更糟。
            return NONE;
        }
    }

    /**
     * 这条已存在的路是不是「当前正在算剖面」的这条。
     *
     * <p>端点键是道路的身份：同一条路的两个端点在规划期就固定了，
     * 所以 {@code (ownerA, ownerB)} 相同即视为自身。
     * 注意方向敏感（A→B 与 B→A 是同一对端点，但存储里的顺序由写入方决定），
     * 因此两种顺序都要认。</p>
     */
    static boolean isSelf(RoadData road, long ownerA2dKey, long ownerB2dKey) {
        long a = road.ownerA2dKey();
        long b = road.ownerB2dKey();
        return (a == ownerA2dKey && b == ownerB2dKey)
                || (a == ownerB2dKey && b == ownerA2dKey);
    }
}