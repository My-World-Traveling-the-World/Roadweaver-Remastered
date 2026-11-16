/* 文件职责：路径控制点简化和密度补点；只处理原始坐标子序列，不访问世界。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** 路径控制点简化和密度补点；只处理原始坐标子序列，不访问世界。 */
final class PathControlSimplifier {
    private PathControlSimplifier() {}

    /**
     * Douglas-Peucker 垂直距离容差（格）。
     *
     * <p>取 4 = {@code RoadConstants.DEFAULT_ASTAR_STEP / 2}，即 A* 网格量化误差的上限：
     * 被删掉的点偏离保留折线不超过半步长，与「网格离散化本身的不确定度」不可区分，
     * 因此不会削掉真实的地形弯曲（沿山谷/绕山脊的弯曲振幅是几十格量级）。</p>
     *
     * <p>0.9.016 之前这里用的是叉积阈值 16，与步长强耦合：步长 8 时 45° 转弯叉积约 45、
     * 90° 为 128，全部大于阈值 ⇒ 几乎不删点 ⇒ A* 的 8 格锯齿原样留在控制点里，
     * 而下游 Catmull-Rom 样条「穿过」每个控制点，锯齿就被画成波浪。步长 1~2 时
     * 反而会全删只剩起终点。换 DP 后阈值与步长解耦。</p>
     */
    static final double SIMPLIFY_TOLERANCE = 4.0D;

    /** 控制点密度下限：每这么多格路径长度至少保留一个控制点，防止把真实弯道拉成直线。 */
    static final double SIMPLIFY_MIN_POINTS_PER_BLOCK = 64.0D;

    /** 容差跟步长走：{@code clamp(0.5×step, 2, SIMPLIFY_TOLERANCE)}（O-13）。 */
    static double toleranceForStep(int gridStep) {
        double half = Math.max(1, gridStep) * 0.5D;
        return Math.max(2.0D, Math.min(SIMPLIFY_TOLERANCE, half));
    }

    static List<BlockPos> simplifyPath(List<BlockPos> nodes) {
        return simplifyPath(nodes, 8);
    }

    /**
     * 步距感知版本（O-13）：容差跟步长走，{@code clamp(0.5×step, 2, 4)}。
     *
     * <p>写死 4 时，一旦有人把 {@code aStarStep} 调到 2（局部修复档就是 2），容差 4
     * 会把 2 格网格上的**所有转角**一起抹平 —— 因为 DP 只看「偏离保留折线多远」。
     * 而默认档 {@code step=8} 时 {@code 0.5×8 = 4} 与原常量完全相同，所以
     * <b>默认档逐位不变</b>。</p>
     */
    static List<BlockPos> simplifyPath(List<BlockPos> nodes, int gridStep) {
        if (nodes == null || nodes.size() < 3) return nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        double totalLength = 0.0D;
        for (int i = 1; i < nodes.size(); i++) {
            totalLength += Math.hypot((double) nodes.get(i).getX() - nodes.get(i - 1).getX(),
                    (double) nodes.get(i).getZ() - nodes.get(i - 1).getZ());
        }
        int minPoints = Math.max(2, (int) Math.ceil(totalLength / SIMPLIFY_MIN_POINTS_PER_BLOCK));
        double tolerance = toleranceForStep(gridStep);
        List<BlockPos> result = simplifyPath(nodes, tolerance);
        if (result.size() >= minPoints) return result;
        // 0.9.019（O-02）：密度下限不满足时，**不再把容差对半砍**。
        //
        // 0.9.016 的写法是「收紧容差重试」，后果是把整个修复自我否决掉：一条
        // 22.5° 锯齿路在容差 4 下只剩 2 个控制点（< minPoints），重试到容差 2 又
        // 退回 61 个点 —— **等于零简化**，锯齿原样进 Catmull-Rom 被画成波浪。
        // 现在改成「带密度约束的 DP」：保持容差 4，按「偏离当前折线最大」的次序
        // 把被删掉的原始点**逐点放回来**，直到达到密度下限。这样既保住容差与步长
        // 解耦的设计，又不会因为一次极端判断把整条路的简化全部作废。
        return reinstateUntilDense(nodes, result, minPoints);
    }

    /**
     * 把偏离当前折线最大的原始点逐点放回，直到点数达到 {@code minPoints}（O-02）。
     *
     * <p>只放回**原始点本身**（XZ 与 Y 都是原值），不移动、不新建任何坐标，所以
     * 结果始终是原始点列的一个子序列 —— 与 DP 的语义一致，几何变化仍有严格上界。</p>
     */
    static List<BlockPos> reinstateUntilDense(List<BlockPos> nodes, List<BlockPos> kept, int minPoints) {
        if (nodes == null || kept == null || kept.size() >= minPoints) return kept;
        int n = nodes.size();
        boolean[] keptFlag = new boolean[n];
        int cursor = 0;
        for (BlockPos point : kept) {
            int found = -1;
            for (int i = cursor; i < n; i++) {
                if (nodes.get(i).equals(point)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) return kept;   // 对不上就放弃，绝不猜
            keptFlag[found] = true;
            cursor = found + 1;
        }
        int keptCount = kept.size();
        while (keptCount < minPoints) {
            int bestIndex = -1;
            double bestDeviation = -1.0D;
            int previousKept = -1;
            for (int i = 0; i < n; i++) {
                if (!keptFlag[i]) continue;
                if (previousKept >= 0 && i - previousKept > 1) {
                    BlockPos a = nodes.get(previousKept);
                    BlockPos b = nodes.get(i);
                    for (int k = previousKept + 1; k < i; k++) {
                        double deviation = perpendicularDistance(nodes.get(k), a, b);
                        if (deviation > bestDeviation) {
                            bestDeviation = deviation;
                            bestIndex = k;
                        }
                    }
                }
                previousKept = i;
            }
            if (bestIndex < 0 || bestDeviation <= 1.0E-9D) break;   // 已无可放回的点
            keptFlag[bestIndex] = true;
            keptCount++;
        }
        List<BlockPos> out = new ArrayList<>(keptCount);
        for (int i = 0; i < n; i++) {
            if (keptFlag[i]) out.add(nodes.get(i));
        }
        return out;
    }

    static double perpendicularDistance(BlockPos point, BlockPos a, BlockPos b) {
        double ax = a.getX();
        double az = a.getZ();
        double dx = b.getX() - ax;
        double dz = b.getZ() - az;
        double lenSq = dx * dx + dz * dz;
        double x = point.getX();
        double z = point.getZ();
        if (lenSq < 1.0E-9D) {
            return Math.hypot(x - ax, z - az);
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lenSq));
        return Math.hypot(x - (ax + t * dx), z - (az + t * dz));
    }

    /**
     * Douglas-Peucker（迭代栈实现，避免深递归）。
     *
     * <p>只删「到保留折线的垂直距离 ≤ tolerance」的点，保留点的 XZ 与 Y 都是原始值，
     * 因此在容差内的几何变化有严格上界。</p>
     */
    static List<BlockPos> simplifyPath(List<BlockPos> nodes, double tolerance) {
        int n = nodes.size();
        if (n < 3) return new ArrayList<>(nodes);
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] span = stack.pop();
            int from = span[0], to = span[1];
            if (to - from < 2) continue;
            double ax = nodes.get(from).getX(), az = nodes.get(from).getZ();
            double dx = (double) nodes.get(to).getX() - ax;
            double dz = (double) nodes.get(to).getZ() - az;
            double length2 = dx * dx + dz * dz;
            double worst = -1.0D;
            int worstIndex = -1;
            for (int i = from + 1; i < to; i++) {
                double px = (double) nodes.get(i).getX() - ax;
                double pz = (double) nodes.get(i).getZ() - az;
                double distance;
                if (length2 <= 1.0E-9D) {
                    distance = Math.hypot(px, pz);
                } else {
                    double t = (px * dx + pz * dz) / length2;
                    if (t < 0.0D) t = 0.0D; else if (t > 1.0D) t = 1.0D;
                    distance = Math.hypot(px - t * dx, pz - t * dz);
                }
                if (distance > worst) {
                    worst = distance;
                    worstIndex = i;
                }
            }
            if (worstIndex >= 0 && worst > tolerance) {
                keep[worstIndex] = true;
                stack.push(new int[]{worstIndex, to});
                stack.push(new int[]{from, worstIndex});
            }
        }
        List<BlockPos> simplified = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) simplified.add(nodes.get(i));
        }
        return simplified;
    }

}
