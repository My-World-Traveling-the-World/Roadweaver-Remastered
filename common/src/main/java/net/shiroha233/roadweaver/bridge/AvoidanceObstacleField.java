/* 文件职责：把现有避让判据采样成势场 —— 判据一字不改，只换表达形式。 */
package net.shiroha233.roadweaver.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.ObstaclePotential;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.core.model.StructureConnection;

/**
 * 把旧仓的避让判据接成度量层要的 {@link ObstacleField}。
 *
 * <h2>本类不改任何判据</h2>
 * <p>「哪些方块算障碍」「村庄包围盒怎么算」「施工高度带多高」仍然完全由
 * {@link StructureAvoidanceService} 决定 —— 用户已明确拍板：
 * <b>村庄裁切包围盒保留原判据</b>（原话「包围盒是我特意做的不用管，
 * 因为有的路会穿村导致切了房屋」）。</p>
 *
 * <p>本类只做一件事：<b>把同一个判据预先采样成一张场</b>，让新求解器不必
 * 在每次迭代里逐点回调判据。</p>
 *
 * <h2>为什么必须预先采样</h2>
 * <p>Fast Marching 每个格点至少查一次代价，一张 30k 点的网格要查上万个位置；
 * 而 {@code blocksPlanningObstacle} 内部要过 ThreadLocal 快照、包围盒相交、
 * 村庄群聚合盒（带缓存但仍有哈希开销）。逐点回调会把判据的常数项放大到主导地位。
 * 一次采样成掩码后，求解器只读数组。</p>
 *
 * <h2>Y 坐标取地表高度</h2>
 * <p>避让判据吃的是 {@code BlockPos}（含 Y），而度量层是二维的
 * （{@code potential(x, z)}）。这里用<b>该列的地表高度</b>作为查询 Y ——
 * 因为道路本来就修在地表：同一栋建筑在 y=120、路面在 y=64 时并不挡路，
 * 而判据内部的施工带是 {@code [y−2, y+6]}，用别的 Y 会得到错误结论。</p>
 *
 * <h2>硬 / 软的对应关系不变</h2>
 * <p>旧实现的语义原样保留：</p>
 * <ul>
 *   <li>{@code PENALTY} 模式下 {@code blocksPlanningObstacle} 恒返回 false ⇒
 *       本适配器<b>不会有不可通行格</b>，只有软势；</li>
 *   <li>{@code HARD_BUILDINGS} 及以上返回 true 的格子 ⇒
 *       {@link ObstacleField#impassable} 为真，求解器<b>不进队列</b>。</li>
 * </ul>
 *
 * <p>由此得到旧实现缺的那条 <b>fail-safe</b>：整条走廊被硬挡死时，场求解会
 * 让对岸不可达，{@code topology} 把端点列进 {@code isolatedNodes}，
 * {@code QualityGate} 拒绝 —— 而不是像 {@code PROTECTED_GAP} 那样
 * 静默放行一条穿建筑的路。</p>
 *
 * <h2>线程与作用域</h2>
 * <p>判据依赖 ThreadLocal 的规划快照。所以生产入口有两档：</p>
 * <ul>
 *   <li>{@link #fromAvoidanceService} —— 调用方<b>必须自己已在</b>
 *       {@code activatePlanningSnapshot(...)} 的作用域内、同一线程；</li>
 *   <li>{@link #sampleWithin} —— 本类代管作用域（推荐）。</li>
 * </ul>
 *
 * @see net.shiroha233.roadweaver.metric.ObstacleField
 * @see AvoidanceObstacleField.BlockingTest
 */
public final class AvoidanceObstacleField {

    private AvoidanceObstacleField() {}

    /**
     * 避让判据的抽象形式，让本类不依赖 Minecraft 也能测。
     *
     * @param x 方块 x
     * @param y 方块 y（查询用，适配器传地表高度）
     * @param z 方块 z
     * @return 该位置是否被判为硬障碍
     */
    @FunctionalInterface
    public interface BlockingTest {
        boolean blocks(int x, int y, int z);
    }

    /**
     * 核心采样逻辑（不依赖 Minecraft，可纯单测）。
     *
     * @param terrain   地形场（只用它的 {@code height(x,z)} 作查询 Y）
     * @param minX      区域最小 x
     * @param minZ      区域最小 z
     * @param width     区域宽
     * @param depth     区域高
     * @param clearance 水平安全间距（原样传给判据）
     * @param falloff   软势的衰减半径（格）
     * @param test      避让判据
     * @return 组合后的势场：软势来自距离变换，硬不可通行来自原始掩码
     */
    public static ObstacleField sample(TerrainField terrain, int minX, int minZ,
                                       int width, int depth, int clearance,
                                       double falloff, BlockingTest test) {
        if (terrain == null) throw new IllegalArgumentException("terrain 不能为 null");
        if (test == null) throw new IllegalArgumentException("test 不能为 null");
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + width + "x" + depth);
        }

        boolean[] blocked = new boolean[width * depth];
        int blockedCount = 0;
        for (int iz = 0; iz < depth; iz++) {
            for (int ix = 0; ix < width; ix++) {
                int x = minX + ix;
                int z = minZ + iz;
                int y = terrain.height(x, z);
                if (test.blocks(x, y, z)) {
                    blocked[ix + iz * width] = true;
                    blockedCount++;
                }
            }
        }

        ObstaclePotential soft = ObstaclePotential.fromMask(
                minX, minZ, width, depth, blocked, falloff);
        return new MaskedPotential(soft, blocked, minX, minZ, width, depth, blockedCount);
    }

    /**
     * 用真实的避让判据采样。
     *
     * <p><b>调用方必须已在 {@code activatePlanningSnapshot(...)} 作用域内、
     * 且在同一线程</b>，否则判据拿到的快照是空的（等价于「什么都挡不住」）。</p>
     */
    public static ObstacleField fromAvoidanceService(TerrainField terrain,
                                                     int minX, int minZ,
                                                     int width, int depth,
                                                     int clearance, double falloff) {
        return sample(terrain, minX, minZ, width, depth, clearance, falloff,
                (x, y, z) -> StructureAvoidanceService.blocksPlanningObstacle(
                        new BlockPos(x, y, z), clearance));
    }

    /**
     * 代管规划快照作用域的采样（推荐入口）。
     *
     * <p>进入作用域 → 采样 → 退出作用域。快照的 ThreadLocal 原值会被恢复，
     * 不会污染调用方的状态。</p>
     *
     * @param level      服务端世界（为空时判据恒不挡，等价于不启用避让）
     * @param connection 当前正在规划的连接
     */
    public static ObstacleField sampleWithin(ServerLevel level,
                                             StructureConnection connection,
                                             TerrainField terrain,
                                             int minX, int minZ,
                                             int width, int depth,
                                             int clearance, double falloff) {
        try (StructureAvoidanceService.PlanningScope ignored =
                     StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
            return fromAvoidanceService(terrain, minX, minZ, width, depth, clearance, falloff);
        }
    }

    /**
     * 组合势场：软势走距离变换，硬不可通行走原始掩码。
     *
     * <p>为什么两者不能合并成一个数：软势要<b>有梯度</b>（让路径知道哪边更远），
     * 硬挡要<b>无梯度</b>（不许穿越）。用一个「很大的软代价」冒充硬约束，
     * 就是旧实现 {@code PROTECTED_GAP} 缺 fail-safe 的成因。</p>
     */
    private record MaskedPotential(ObstaclePotential soft,
                                   boolean[] blocked,
                                   int minX, int minZ, int width, int depth,
                                   int blockedCount) implements ObstacleField {

        @Override
        public double potential(int x, int z) {
            return soft.potential(x, z);
        }

        @Override
        public boolean impassable(int x, int z) {
            if (x < minX || x >= minX + width || z < minZ || z >= minZ + depth) {
                return true;            // 区域外一律当不可通行，防止求解器走出去
            }
            return blocked[(x - minX) + (z - minZ) * width];
        }

        /** 便于日志。 */
        String describe() {
            return "avoidance: region=" + width + "x" + depth
                    + " blocked=" + blockedCount
                    + " (" + String.format(java.util.Locale.ROOT, "%.1f",
                    100.0 * blockedCount / (width * depth)) + "%)"
                    + " falloff=" + soft.falloff();
        }
    }
}
