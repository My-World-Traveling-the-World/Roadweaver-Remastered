/* 文件职责：冻结bc69e3b的样条/高度投影/宽度落列，防止拆分两侧调用同一实现而循环对拍。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import java.util.*;

/**
 * 只冻结本轮迁出的三个算法及它们的数学函数；不能随生产实现改写这份参照。
 * 桥掩码、控制点简化/松弛/密化仍共享原入口，不用它宣称这些未修改阶段也有独立对拍。
 * 数学函数移出Vec2d所在外部类后只把私有字段读改成等价record访问器，运算顺序未改。
 */
public final class LegacyCurveRasterReference {
    private static final double SHARP_TURN_FREEZE_DEG = 60.0D;
    private LegacyCurveRasterReference() { }

    // 从真实生产入口的相同准备阶段进入冻结后半段，覆盖搬迁后接线及最终逐站落列。
    public static List<RoadSegmentPlacement> process(List<BlockPos> raw, int width,
            PathTerrainField terrain, AccurateHeightSampler sampler, CurveMode mode) {
        int[] targetY = raw.stream().mapToInt(BlockPos::getY).toArray();
        int step = PathPostProcessor.deriveGridStep(raw);
        var simplified = PathPostProcessor.simplifyPath(raw, step);
        var mask = BridgeMaskTool.detectBridgeMask(simplified, null, null, terrain, 1, sampler);
        var straight = BridgeMaskTool.straightenBridgeRuns(simplified, mask);
        var controls = PathPostProcessor.relaxPathSkippingBridge(straight, mask);
        var dense = PathPostProcessor.densifyControlPoints(controls, mask, step);
        var points = generateSplinePoints(dense.points(), dense.mask(), mode);
        var distances = new ArrayList<Double>();
        var centers = extractCenters(points, raw, targetY, distances);
        return rasterizeSegments(points, centers, distances, width);
    }

    /** 第 {@code i} 条腿（控制点 i → i+1）是否属于桥面（任一端点在桥上）。 */
    static boolean bridgeTouching(boolean[] bridgeMask, int i) {
        if (bridgeMask == null || i < 0 || i >= bridgeMask.length - 1) return false;
        return bridgeMask[i] || bridgeMask[i + 1];
    }

    static List<Vec2d> generateSplinePoints(List<BlockPos> controlPoints,
                                                     boolean[] bridgeMask, CurveMode curveMode) {
        List<BlockPos> ext = new ArrayList<>();
        ext.add(controlPoints.get(0));
        ext.addAll(controlPoints);
        ext.add(controlPoints.get(controlPoints.size() - 1));

        // 0.9.068（C3）：急弯段用直线代替 Catmull-Rom，避免锐角（发卡弯）外冲。
        // C2 只保护了 relax 阶段；这里在样条阶段对转向角 >= 阈值的控制点两端改用 lerp，
        // 发卡弯顶点不再被 CR 的切线 (P_{i+1}-P_{i-1})/2 甩出去。
        int nCtrl = controlPoints.size();
        boolean[] sharpAt = new boolean[nCtrl];
        for (int k = 1; k < nCtrl - 1; k++) {
            BlockPos ca = controlPoints.get(k - 1), cb = controlPoints.get(k), cc = controlPoints.get(k + 1);
            sharpAt[k] = turningAngleDeg(ca.getX(), ca.getZ(), cb.getX(), cb.getZ(), cc.getX(), cc.getZ())
                    >= SHARP_TURN_FREEZE_DEG;
        }

        List<Vec2d> splinePoints = new ArrayList<>();
        for (int i = 0; i < controlPoints.size() - 1; i++) {
            BlockPos p0 = ext.get(i), p1 = ext.get(i + 1), p2 = ext.get(i + 2), p3 = ext.get(i + 3);
            double dist = Math.sqrt(p1.distSqr(p2));
            int steps = Math.max(1, (int) Math.ceil(dist * 4.0));
            boolean bridgeSeg = bridgeTouching(bridgeMask, i);
            // 0.9.019（O-04）：桥头切线对齐。
            //
            // 桥段用 lerp（直线）、桥外仍用 Catmull-Rom，于是桥界两侧的**切向不连续**：
            // CR 在结点处的切向取 (P_{i+1} − P_{i-1})/2，桥面离开结点时沿
            // (P_{i+1} − P_i)，两者之差就是 (P_i − P_{i-1}) —— 分册实测桥头外侧
            // 航向突变 −30.82°/+30.82°。修法：把桥段**前后各一条腿**也用直线，
            // 这样两边都是直线、切向自然一致。密化之后每条腿 ≤ 步距，
            // 所以每个桥头最多只直 8~16 格，桥面本身一格不动。
            boolean tangentAligned = bridgeSeg
                    || bridgeTouching(bridgeMask, i - 1)
                    || bridgeTouching(bridgeMask, i + 1);
            // 0.9.068（C3）：急弯段（任一端点转向角超阈值）用直线，不跑 CR。
            boolean sharpSeg = sharpAt[i] || sharpAt[i + 1];

            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                Vec2d pt;
                if (tangentAligned || sharpSeg || curveMode == CurveMode.LINEAR) {
                    pt = lerp2d(p1.getX(), p1.getZ(), p2.getX(), p2.getZ(), t);
                } else if (curveMode == CurveMode.BEZIER_CASTELJAU) {
                    pt = catmullRomBezierCasteljau(p0.getX(), p0.getZ(), p1.getX(), p1.getZ(),
                            p2.getX(), p2.getZ(), p3.getX(), p3.getZ(), t);
                } else {
                    pt = catmullRomSpline(p0.getX(), p0.getZ(), p1.getX(), p1.getZ(),
                            p2.getX(), p2.getZ(), p3.getX(), p3.getZ(), t);
                }
                splinePoints.add(pt);
            }
        }
        BlockPos last = controlPoints.get(controlPoints.size() - 1);
        splinePoints.add(new Vec2d(last.getX(), last.getZ()));
        return splinePoints;
    }

    static List<BlockPos> extractCenters(List<Vec2d> splinePoints,
                                                  List<BlockPos> rawPath, int[] rawTargetY,
                                                  List<Double> outCenterDists) {
        List<BlockPos> centers = new ArrayList<>();
        double currentDist = 0;
        double nextCenterDist = 0;
        int rawSegmentHint = 0;

        for (int i = 0; i < splinePoints.size(); i++) {
            Vec2d p = splinePoints.get(i);
            if (i > 0) currentDist += p.dist(splinePoints.get(i - 1));

            if (currentDist + 0.5 >= nextCenterDist || i == splinePoints.size() - 1) {
                int cx = (int) Math.round(p.x());
                int cz = (int) Math.round(p.z());
                HeightProjection projection = interpolateYNearPathOrder(
                        cx, cz, rawPath, rawTargetY, rawSegmentHint);
                int y = projection.y();
                rawSegmentHint = projection.segmentIndex();
                BlockPos centerPos = new BlockPos(cx, y, cz);
                if (centers.isEmpty() || !centers.get(centers.size() - 1).equals(centerPos)) {
                    centers.add(centerPos);
                    outCenterDists.add(currentDist);
                    nextCenterDist = currentDist + 1.0;
                }
            }
        }
        return centers;
    }

    static HeightProjection interpolateYNearPathOrder(int cx,
                                                               int cz,
                                                               List<BlockPos> rawPath,
                                                               int[] rawTargetY,
                                                               int hint) {
        double bestDistSq = Double.MAX_VALUE;
        double bestT = 0;
        int bestSeg = Math.max(0, Math.min(rawPath.size() - 2, hint));
        int searchStart = bestSeg;
        int searchEnd = Math.min(rawPath.size() - 2, bestSeg + 32);

        for (int i = searchStart; i <= searchEnd; i++) {
            BlockPos a = rawPath.get(i);
            BlockPos b = rawPath.get(i + 1);
            double ax = a.getX(), az = a.getZ();
            double dx = b.getX() - ax, dz = b.getZ() - az;
            double lenSq = dx * dx + dz * dz;
            double t = (lenSq < 1e-9) ? 0.0
                    : Math.max(0.0, Math.min(1.0, ((cx - ax) * dx + (cz - az) * dz) / lenSq));
            double projX = ax + t * dx, projZ = az + t * dz;
            double dSq = (cx - projX) * (cx - projX) + (cz - projZ) * (cz - projZ);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestT = t;
                bestSeg = i;
            }
        }
        int y0 = rawTargetY[bestSeg];
        int y1 = rawTargetY[Math.min(bestSeg + 1, rawTargetY.length - 1)];
        return new HeightProjection(
                (int) Math.round(y0 + (y1 - y0) * bestT), bestSeg);
    }

    private record HeightProjection(int y, int segmentIndex) {
    }

    static List<RoadSegmentPlacement> rasterizeSegments(List<Vec2d> splinePoints,
                                                                 List<BlockPos> centers,
                                                                 List<Double> centerDists,
                                                                 int width) {
        Map<Integer, Set<BlockPos>> segmentedBlocks = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) segmentedBlocks.put(i, new HashSet<>());

        double halfWidth = width / 2.0;
        double halfWidthSq = halfWidth * halfWidth;
        double pathDist = 0;
        int currentCenterIdx = 0;

        for (int i = 0; i < splinePoints.size() - 1; i++) {
            Vec2d pStart = splinePoints.get(i), pEnd = splinePoints.get(i + 1);
            pathDist += pStart.dist(pEnd);
            while (currentCenterIdx < centerDists.size() - 1 && centerDists.get(currentCenterIdx + 1) < pathDist) {
                currentCenterIdx++;
            }

            int minX = (int) Math.floor(Math.min(pStart.x(), pEnd.x()) - halfWidth - 1);
            int maxX = (int) Math.ceil(Math.max(pStart.x(), pEnd.x()) + halfWidth + 1);
            int minZ = (int) Math.floor(Math.min(pStart.z(), pEnd.z()) - halfWidth - 1);
            int maxZ = (int) Math.ceil(Math.max(pStart.z(), pEnd.z()) + halfWidth + 1);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (distToSegmentSq(x, z, pStart, pEnd) <= halfWidthSq) {
                        assignToNearestCenter(x, z, centers, currentCenterIdx, segmentedBlocks);
                    }
                }
            }
        }

        List<RoadSegmentPlacement> out = new ArrayList<>();
        for (int i = 0; i < centers.size(); i++) {
            BlockPos center = centers.get(i);
            Set<BlockPos> blocks = segmentedBlocks.get(i);
            if (blocks.isEmpty()) blocks.add(new BlockPos(center.getX(), 0, center.getZ()));
            out.add(new RoadSegmentPlacement(center, new ArrayList<>(blocks)));
        }
        return out;
    }

    static void assignToNearestCenter(int x, int z, List<BlockPos> centers,
                                               int hint, Map<Integer, Set<BlockPos>> segmentedBlocks) {
        int bestIdx = hint;
        double bestDistSq = Double.MAX_VALUE;
        int searchStart = Math.max(0, hint - 2);
        int searchEnd = Math.min(centers.size() - 2, hint + 2);

        for (int seg = searchStart; seg <= searchEnd; seg++) {
            BlockPos a = centers.get(seg), b = centers.get(seg + 1);
            double ax = a.getX(), az = a.getZ(), dx = b.getX() - ax, dz = b.getZ() - az;
            double lenSq = dx * dx + dz * dz;
            double t = (lenSq < 1e-9) ? 0.0
                    : Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / lenSq));
            double projX = ax + t * dx, projZ = az + t * dz;
            double dSq = (x - projX) * (x - projX) + (z - projZ) * (z - projZ);
            int candidateIndex = (t < 0.5) ? seg : Math.min(seg + 1, centers.size() - 1);
            int currentDistance = Math.abs(bestIdx - hint);
            int candidateDistance = Math.abs(candidateIndex - hint);
            if (dSq < bestDistSq - 1.0E-9D
                    || (Math.abs(dSq - bestDistSq) <= 1.0E-9D && candidateDistance < currentDistance)) {
                bestDistSq = dSq;
                bestIdx = candidateIndex;
            }
        }
        segmentedBlocks.get(bestIdx).add(new BlockPos(x, 0, z));
    }

    static double turningAngleDeg(int ax, int az, int bx, int bz, int cx, int cz) {
        double v1x = bx - ax, v1z = bz - az;
        double v2x = cx - bx, v2z = cz - bz;
        double len1 = Math.hypot(v1x, v1z);
        double len2 = Math.hypot(v2x, v2z);
        if (len1 < 1.0E-9D || len2 < 1.0E-9D) return 0.0D;
        double cos = (v1x * v2x + v1z * v2z) / (len1 * len2);
        cos = Math.max(-1.0D, Math.min(1.0D, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    public static Vec2d lerp2d(double ax, double az, double bx, double bz, double t) {
        return new Vec2d(ax + (bx - ax) * t, az + (bz - az) * t);
    }

    public static Vec2d catmullRomSpline(double x0, double z0, double x1, double z1,
                                         double x2, double z2, double x3, double z3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double f0 = -0.5 * t3 + t2 - 0.5 * t;
        double f1 = 1.5 * t3 - 2.5 * t2 + 1.0;
        double f2 = -1.5 * t3 + 2.0 * t2 + 0.5 * t;
        double f3 = 0.5 * t3 - 0.5 * t2;
        return new Vec2d(x0 * f0 + x1 * f1 + x2 * f2 + x3 * f3,
                         z0 * f0 + z1 * f1 + z2 * f2 + z3 * f3);
    }

    public static Vec2d catmullRomBezierCasteljau(double x0, double z0, double x1, double z1,
                                                   double x2, double z2, double x3, double z3, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        Vec2d b0 = new Vec2d(x1, z1);
        Vec2d b1 = new Vec2d(x1 + (x2 - x0) / 6.0, z1 + (z2 - z0) / 6.0);
        Vec2d b2 = new Vec2d(x2 - (x3 - x1) / 6.0, z2 - (z3 - z1) / 6.0);
        Vec2d b3 = new Vec2d(x2, z2);
        Vec2d[] elevated = elevateBezierDegree(new Vec2d[]{b0, b1, b2, b3}, 5);
        return bezierDeCasteljau(elevated, t);
    }

    static Vec2d[] elevateBezierDegree(Vec2d[] ctrl, int targetDegree) {
        if (ctrl == null || ctrl.length < 2) return ctrl;
        int degree = ctrl.length - 1;
        if (targetDegree <= degree) return ctrl;

        Vec2d[] cur = ctrl;
        int n = degree;
        while (n < targetDegree) {
            Vec2d[] next = new Vec2d[n + 2];
            next[0] = cur[0];
            next[n + 1] = cur[n];
            for (int i = 1; i <= n; i++) {
                double a = (double) i / (double) (n + 1);
                next[i] = new Vec2d(a * cur[i - 1].x() + (1.0 - a) * cur[i].x(),
                                    a * cur[i - 1].z() + (1.0 - a) * cur[i].z());
            }
            cur = next;
            n++;
        }
        return cur;
    }

    static Vec2d bezierDeCasteljau(Vec2d[] ctrl, double t) {
        int n = ctrl.length;
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = ctrl[i].x();
            zs[i] = ctrl[i].z();
        }
        for (int k = n - 1; k > 0; k--) {
            for (int i = 0; i < k; i++) {
                xs[i] = xs[i] + (xs[i + 1] - xs[i]) * t;
                zs[i] = zs[i] + (zs[i + 1] - zs[i]) * t;
            }
        }
        return new Vec2d(xs[0], zs[0]);
    }

    public static double distToSegmentSq(double px, double pz, Vec2d v, Vec2d w) {
        double l2 = v.distSqr(w);
        if (l2 == 0) return (px - v.x()) * (px - v.x()) + (pz - v.z()) * (pz - v.z());
        double t = ((px - v.x()) * (w.x() - v.x()) + (pz - v.z()) * (w.z() - v.z())) / l2;
        t = Math.max(0, Math.min(1, t));
        double projX = v.x() + t * (w.x() - v.x());
        double projZ = v.z() + t * (w.z() - v.z());
        return (px - projX) * (px - projX) + (pz - projZ) * (pz - projZ);
    }
}
