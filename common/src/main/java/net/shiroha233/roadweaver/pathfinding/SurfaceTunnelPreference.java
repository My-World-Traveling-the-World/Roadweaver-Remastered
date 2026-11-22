/* 文件职责：整段可走缓坡地表时，不把山体突起直接当成隧道。 */
package net.shiroha233.roadweaver.pathfinding;

import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;

final class SurfaceTunnelPreference {
    private static final double GRADE_WINDOW_BLOCKS = 16.0D;
    private SurfaceTunnelPreference() {}

    static boolean canUseSurface(List<BlockPos> centers, List<Integer> targetY,
                                 int first, int last, double softGrade, IntPredicate landAndFits) {
        int left = first;
        double length = 0.0D;
        double grade = Math.max(0.0D, softGrade);
        for (int i = first; i <= last; i++) {
            if (!landAndFits.test(i)) return false;
            if (i == first) continue;
            double step = distance(centers.get(i - 1), centers.get(i));
            // 一格量化容差允许普通半砖接坡，但不能把连续整格陡台阶都算作缓坡。
            if (rise(targetY, i - 1, i) > 1.0D + step * grade) return false;
            length += step;
            while (left + 1 < i) {
                double leaving = distance(centers.get(left), centers.get(left + 1));
                if (length - leaving < GRADE_WINDOW_BLOCKS) break;
                length -= leaving;
                left++;
            }
            // 短候选按全段测；长候选滚动检查约一块区块长的纵坡，避免山顶两侧升降抵消。
            if ((length >= GRADE_WINDOW_BLOCKS || i == last)
                    && rise(targetY, left, i) > 1.0D + length * grade) return false;
        }
        return true;
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.hypot((double) a.getX() - b.getX(), (double) a.getZ() - b.getZ());
    }

    private static long rise(List<Integer> heights, int a, int b) {
        return Math.abs((long) heights.get(b) - heights.get(a));
    }
}
