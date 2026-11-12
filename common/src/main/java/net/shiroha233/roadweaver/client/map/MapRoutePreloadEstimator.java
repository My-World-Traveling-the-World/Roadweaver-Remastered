/* 文件职责：手绘路线「将加载多少区块」的估算 —— 纯几何，不碰世界也不碰界面。 */
package net.shiroha233.roadweaver.client.map;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import net.shiroha233.roadweaver.route.RoutePoint;

/**
 * 路线预加载量的估算。
 *
 * <h2>它是「提示」不是「精算」</h2>
 * <p>确认框里显示「将加载 N 区块 / 其中 M 未探索」，让玩家在点确认前知道这一笔有多大。
 * 精确值由服务端 {@code RouteChunkPreloader} 在真正预加载时算 ——
 * 这里只是量级提示，所以刻意允许近似。</p>
 *
 * <h2>为什么必须有采样预算</h2>
 * <p>估算跑在<b>渲染线程</b>（点鼠标的那一刻）。一条几千控制点的长路线逐段按 8 格
 * 步进会采几万次，直接把一帧卡住。所以先用 {@code wanted} 估出总采样次数，
 * 超过 {@code SAMPLE_BUDGET} 就<b>按比例放大步长</b> —— 区块数只影响提示数字，
 * 采稀一点完全可接受。</p>
 *
 * <h2>两处刻意的保守设计</h2>
 * <ul>
 *   <li>区块集合<b>外扩 1 圈</b>：与服务端预加载口径一致，否则玩家看到的数字偏小、
 *       实际卡得更久；</li>
 *   <li>{@code MAX_CHUNKS} 硬上限：即使预算估算失准也不会无限膨胀。</li>
 * </ul>
 *
 * @see RoutePoint
 */
public final class MapRoutePreloadEstimator {

    /** 全路线最多采这么多次（渲染线程不能卡帧）。 */
    public static final int SAMPLE_BUDGET = 4_000;

    /** 区块数硬上限。 */
    public static final int MAX_CHUNKS = 20_000;

    /** 沿线基础步进（格）。 */
    public static final double SAMPLE_STEP_BLOCKS = 8.0;

    private MapRoutePreloadEstimator() {}

    /**
     * 估算结果。
     *
     * @param segments     段数（= 控制点数 − 1）
     * @param roads        按起点 kind 归属为道路的段数
     * @param bridges      归属为桥的段数
     * @param tunnels      归属为隧道的段数
     * @param lengthBlocks 路线总长（格）
     * @param chunkKeys    会涉及的区块键（已外扩 1 圈）
     */
    public record Estimate(int segments, int roads, int bridges, int tunnels,
                           double lengthBlocks, Set<Long> chunkKeys) {

        public Estimate {
            chunkKeys = Set.copyOf(chunkKeys);
        }

        /** 涉及区块数。 */
        public int chunkCount() { return chunkKeys.size(); }
    }

    /**
     * 估算。
     *
     * <p>少于 2 个控制点时返回全零结果 —— 调用方本来也不该弹确认框。</p>
     */
    public static Estimate estimate(List<RoutePoint> points) {
        if (points == null || points.size() < 2) {
            return new Estimate(0, 0, 0, 0, 0.0, Set.of());
        }
        int n = points.size();

        // ① 先估总采样次数，决定步长
        int wanted = 0;
        for (int i = 0; i + 1 < n; i++) {
            BlockPos a = points.get(i).pos();
            BlockPos b = points.get(i + 1).pos();
            wanted += (int) Math.ceil(Math.max(
                    Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()))
                    / SAMPLE_STEP_BLOCKS) + 1;
        }
        double stride = wanted <= SAMPLE_BUDGET ? 1.0 : (double) wanted / SAMPLE_BUDGET;

        // ② 逐段分类、累计长度、采区块
        int road = 0, bridge = 0, tunnel = 0;
        double length = 0.0;
        Set<Long> chunks = new HashSet<>();
        for (int i = 0; i + 1 < n; i++) {
            BlockPos a = points.get(i).pos();
            BlockPos b = points.get(i + 1).pos();
            switch (points.get(i).kind()) {
                case ROAD -> road++;
                case BRIDGE -> bridge++;
                case TUNNEL -> tunnel++;
            }
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            length += Math.sqrt(dx * dx + dz * dz);

            double stepLen = SAMPLE_STEP_BLOCKS * stride;
            int steps = (int) Math.max(1, Math.ceil(
                    Math.max(Math.abs(dx), Math.abs(dz)) / stepLen));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int x = (int) Math.round(a.getX() + dx * t);
                int z = (int) Math.round(a.getZ() + dz * t);
                chunks.add(ChunkPos.asLong(x >> 4, z >> 4));
            }
            // 保险：即使预算估算失准也不至于无限循环
            if (chunks.size() > MAX_CHUNKS) break;
        }

        // ③ 外扩 1 圈（与服务端预加载口径一致，让玩家看到真实的量级）
        Set<Long> expanded = new HashSet<>();
        for (long key : chunks) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    expanded.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }

        return new Estimate(n - 1, road, bridge, tunnel, length, expanded);
    }
}
