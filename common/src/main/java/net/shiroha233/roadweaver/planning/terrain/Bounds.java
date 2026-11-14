/* 文件职责：XZ 平面上的矩形搜索边界与坐标工具 —— 从 AccurateGradeRepairPlanner 抽出。 */
package net.shiroha233.roadweaver.planning.terrain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 局部修复用的网格搜索边界（2026-11-15 从 {@link AccurateGradeRepairPlanner} 抽出）。
 *
 * <p><b>为什么搬到独立文件：</b>{@code AccurateGradeRepairPlanner} 已到 1006 行，
 * 超出「单文件不超过 1000 行」的约束。本类是完整的几何工具（含对齐、枚举、包含判定），
 * 与「坡度修复流程」职责不同，是最自然的分割点。</p>
 *
 * <p><b>为什么外部引用不需要改：</b>它和唯一的使用方（{@code LakeRiverRepair}、
 * {@code LocalRouteSearch} 等）同属 {@code planning.terrain} 包，Java 同包引用免 import。</p>
 *
 * <h2>为什么所有方法都把坐标吸附到 step 网格</h2>
 * <p>局部搜索是**按网格采样**的：如果边界不在网格上，枚举出来的点会与
 * 主搜索的空间划分错位，导致「明明在边界内却取不到」，或者同一格被两个窗口重复访问。
 * 所以 {@code around} 系列用 {@link Math#floorDiv} 向负无穷方向对齐 ——
 * 用普通除法会在负坐标处向零取整，产生不对称的边界。</p>
 */
public record Bounds(int minX, int minZ, int maxX, int maxZ) {

    /** 按路径点集 + 外扩余量构造边界，不含障碍盒。 */
    static Bounds around(List<BlockPos> path, int margin, int step) {
        return around(path, margin, step, null);
    }

    /**
     * 按路径点集 + 外扩余量构造边界，并并入一个障碍盒。
     *
     * <p>把障碍盒并进来是必要的：绕行冲突时目标点常在障碍另一侧，
     * 只按路径点取边界会把绕行路线整个切掉。</p>
     */
    static Bounds around(List<BlockPos> path, int margin, int step, BoundingBox obstacle) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos position : path) {
            minX = Math.min(minX, position.getX());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxZ = Math.max(maxZ, position.getZ());
        }
        if (obstacle != null) {
            minX = Math.min(minX, obstacle.minX());
            minZ = Math.min(minZ, obstacle.minZ());
            maxX = Math.max(maxX, obstacle.maxX());
            maxZ = Math.max(maxZ, obstacle.maxZ());
        }
        minX = Math.floorDiv(minX - margin, step) * step;
        minZ = Math.floorDiv(minZ - margin, step) * step;
        maxX = Math.floorDiv(maxX + margin + step - 1, step) * step;
        maxZ = Math.floorDiv(maxZ + margin + step - 1, step) * step;
        return new Bounds(minX, minZ, maxX, maxZ);
    }

    /**
     * 以冲突点为中心、半径为 {@code radius} 的正方形边界，
     * 并并入起终点与障碍盒 —— 保证绕行路线的两端一定在窗口内。
     */
    static Bounds aroundConflict(BlockPos center, int radius, int step,
                                 BlockPos start, BlockPos goal, BoundingBox obstacle) {
        int minX = center.getX() - radius;
        int minZ = center.getZ() - radius;
        int maxX = center.getX() + radius;
        int maxZ = center.getZ() + radius;
        if (start != null) {
            minX = Math.min(minX, start.getX());
            minZ = Math.min(minZ, start.getZ());
            maxX = Math.max(maxX, start.getX());
            maxZ = Math.max(maxZ, start.getZ());
        }
        if (goal != null) {
            minX = Math.min(minX, goal.getX());
            minZ = Math.min(minZ, goal.getZ());
            maxX = Math.max(maxX, goal.getX());
            maxZ = Math.max(maxZ, goal.getZ());
        }
        if (obstacle != null) {
            minX = Math.min(minX, obstacle.minX());
            minZ = Math.min(minZ, obstacle.minZ());
            maxX = Math.max(maxX, obstacle.maxX());
            maxZ = Math.max(maxZ, obstacle.maxZ());
        }
        minX = Math.floorDiv(minX, step) * step;
        minZ = Math.floorDiv(minZ, step) * step;
        maxX = Math.floorDiv(maxX + step - 1, step) * step;
        maxZ = Math.floorDiv(maxZ + step - 1, step) * step;
        return new Bounds(minX, minZ, maxX, maxZ);
    }

    /** 枚举边界内所有网格点（y 恒为 0，调用方按需补高度）。 */
    Collection<BlockPos> positions(int step) {
        Map<Long, BlockPos> unique = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                unique.put(key(x, z), new BlockPos(x, 0, z));
            }
        }
        return unique.values();
    }

    /** 枚举边界内、且落在以 {@code center} 为心 {@code radius} 圆内的网格点。 */
    Collection<BlockPos> positionsWithinRadius(int step, BlockPos center, int radius) {
        Map<Long, BlockPos> unique = new LinkedHashMap<>();
        long radiusSquared = (long) radius * radius;
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                if (horizontalDistanceSquared(x, z, center.getX(), center.getZ()) <= radiusSquared) {
                    unique.put(key(x, z), new BlockPos(x, 0, z));
                }
            }
        }
        return unique.values();
    }

    /** 该 XZ 是否落在边界内（含边界本身）。 */
    boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    // ── 坐标工具（从 AccurateGradeRepairPlanner 一并搬来，与 Bounds 同职责）──

    /**
     * XZ 平面距离的平方。
     *
     * <p>全程用 {@code long}：坐标差可达数十万，{@code int} 平方会溢出。
     * 返回平方值是为了让调用方与半径平方比较，避免开方误差。</p>
     */
    public static long horizontalDistanceSquared(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    /**
     * 把 XZ 打包成一个 long 作为 {@code Map} 键。
     *
     * <p>高 32 位放 x、低 32 位放 z。{@code z & 0xffffffffL} 是为了保留负 z 的位型 ——
     * 直接移位会让负 z 与正 z 撞键。</p>
     */
    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}