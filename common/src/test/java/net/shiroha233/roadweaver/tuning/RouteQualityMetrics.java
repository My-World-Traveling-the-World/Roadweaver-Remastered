/* 文件职责：从原始路径计算离线质量指标（贴地度/突兀度/涉水/回头弯/直线效率）。 */
package net.shiroha233.roadweaver.tuning;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 一条原始路径（势场法 {@code findRawPath} 的输出）相对合成地形的质量画像。
 *
 * <p>五项核心指标对应调参主指标：
 * <ul>
 *   <li>贴地度 → {@link #avgGrade} / {@link #maxGrade}（越小越平顺）；</li>
 *   <li>突兀度 → {@link #steepEdgeCount}（陡坡段数）+ {@link #sharpTurnCount}（急转弯数）；</li>
 *   <li>涉水不当 → {@link #waterNodeCount}（路径上落入水柱的节点数）；</li>
 *   <li>回头弯 → {@link #hairpinCount}（方向反转 &gt;150° 的拐点）；</li>
 *   <li>直线效率 → {@link #straightness}（直线距离/路径长度，越接近 1 越好，硬约束）。</li>
 * </ul>
 *
 * <p>全部为纯函数：同一路径 + 同一地形必然得到同一结果。
 */
public final class RouteQualityMetrics {

    public final int nodeCount;
    public final double pathLength;
    public final double straightLine;
    public final double straightness;
    public final double totalClimb;
    public final double avgGrade;
    public final double maxGrade;
    public final int steepEdgeCount;
    public final int waterNodeCount;
    public final int sharpTurnCount;
    public final int hairpinCount;
    /** 缓弯数：相邻两段夹角落在 (GENTLE_TURN_DEG, SHARP_TURN_DEG] —— 平滑蜿蜒的组成部分。 */
    public final int gentleTurnCount;
    /** 总转角：全路径相邻两段夹角绝对值之和（度）。蜿蜒路总转角大但单次转角小；抖动的路两者都大。 */
    public final double totalTurnDeg;

    /** 坡度超过该值视为陡坡段。 */
    public static final double STEEP_GRADE = 0.35;
    /** 相邻两段方向夹角超过该值视为急转弯（抖动/硬拐）。 */
    public static final double SHARP_TURN_DEG = 90.0;
    /** 相邻两段方向夹角超过该值视为回头弯。 */
    public static final double HAIRPIN_DEG = 150.0;
    /** 相邻两段方向夹角超过该值但不超过 SHARP_TURN_DEG 视为「缓弯」（平滑蜿蜒）。 */
    public static final double GENTLE_TURN_DEG = 20.0;

    private RouteQualityMetrics(int nodeCount, double pathLength, double straightLine, double straightness,
                                double totalClimb, double avgGrade, double maxGrade, int steepEdgeCount,
                                int waterNodeCount, int sharpTurnCount, int hairpinCount,
                                int gentleTurnCount, double totalTurnDeg) {
        this.nodeCount = nodeCount;
        this.pathLength = pathLength;
        this.straightLine = straightLine;
        this.straightness = straightness;
        this.totalClimb = totalClimb;
        this.avgGrade = avgGrade;
        this.maxGrade = maxGrade;
        this.steepEdgeCount = steepEdgeCount;
        this.waterNodeCount = waterNodeCount;
        this.sharpTurnCount = sharpTurnCount;
        this.hairpinCount = hairpinCount;
        this.gentleTurnCount = gentleTurnCount;
        this.totalTurnDeg = totalTurnDeg;
    }

    /** 只读水柱探测：{@link SyntheticTerrain} 与 {@link RealTerrain} 都满足。 */
    @FunctionalInterface
    public interface WaterProbe {
        boolean isWater(int x, int z);
    }

    public static RouteQualityMetrics of(List<BlockPos> path, WaterProbe terrain) {
        int n = path.size();
        if (n < 2) {
            return new RouteQualityMetrics(n, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0.0);
        }

        int waterNodes = 0;
        for (BlockPos p : path) {
            if (terrain.isWater(p.getX(), p.getZ())) {
                waterNodes++;
            }
        }

        double pathLength = 0.0;
        double totalClimb = 0.0;
        double maxGrade = 0.0;
        int steepEdges = 0;
        for (int i = 1; i < n; i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            double horiz = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
            int dy = Math.abs(b.getY() - a.getY());
            pathLength += horiz;
            totalClimb += dy;
            if (horiz > 1e-6) {
                double grade = dy / horiz;
                if (grade > maxGrade) {
                    maxGrade = grade;
                }
                if (grade > STEEP_GRADE) {
                    steepEdges++;
                }
            }
        }

        int sharpTurns = 0;
        int hairpins = 0;
        int gentleTurns = 0;
        double totalTurnDeg = 0.0;
        for (int i = 1; i < n - 1; i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            BlockPos c = path.get(i + 1);
            double dx1 = b.getX() - a.getX();
            double dz1 = b.getZ() - a.getZ();
            double dx2 = c.getX() - b.getX();
            double dz2 = c.getZ() - b.getZ();
            double l1 = Math.hypot(dx1, dz1);
            double l2 = Math.hypot(dx2, dz2);
            if (l1 < 1e-6 || l2 < 1e-6) {
                continue;
            }
            double cosAngle = (dx1 * dx2 + dz1 * dz2) / (l1 * l2);
            cosAngle = Math.max(-1.0, Math.min(1.0, cosAngle));
            double deg = Math.toDegrees(Math.acos(cosAngle));
            totalTurnDeg += deg;
            if (deg > SHARP_TURN_DEG) {
                sharpTurns++;
            } else if (deg > GENTLE_TURN_DEG) {
                gentleTurns++;
            }
            if (deg > HAIRPIN_DEG) {
                hairpins++;
            }
        }

        BlockPos start = path.get(0);
        BlockPos end = path.get(n - 1);
        double straightLine = Math.hypot(end.getX() - start.getX(), end.getZ() - start.getZ());
        double straightness = pathLength > 1e-6 ? straightLine / pathLength : 1.0;
        double avgGrade = pathLength > 1e-6 ? totalClimb / pathLength : 0.0;

        return new RouteQualityMetrics(n, pathLength, straightLine, straightness,
                totalClimb, avgGrade, maxGrade, steepEdges, waterNodes, sharpTurns, hairpins,
                gentleTurns, totalTurnDeg);
    }

    public static String csvHeader() {
        return "seed,success,nodes,pathLength,straightness,totalClimb,avgGrade,maxGrade,"
                + "steepEdges,waterNodes,sharpTurns,hairpins,gentleTurns,totalTurnDeg";
    }

    public String toCsv(long seed) {
        return seed + ",true," + nodeCount
                + "," + f(pathLength)
                + "," + f(straightness)
                + "," + f(totalClimb)
                + "," + f(avgGrade)
                + "," + f(maxGrade)
                + "," + steepEdgeCount
                + "," + waterNodeCount
                + "," + sharpTurnCount
                + "," + hairpinCount
                + "," + gentleTurnCount
                + "," + f(totalTurnDeg);
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
