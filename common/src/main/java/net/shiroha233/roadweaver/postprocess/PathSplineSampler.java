/* 文件职责：把控制点采成水平样条，并按原始站序投影目标高度；保持桥头和急弯的参数化规则。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import java.util.*;

import net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode;
import static net.shiroha233.roadweaver.postprocess.SplineHelper.*;
import static net.shiroha233.roadweaver.postprocess.PathControlRelaxer.SHARP_TURN_FREEZE_DEG;
import static net.shiroha233.roadweaver.postprocess.PathControlRelaxer.turningAngleDeg;

// 只接收几何与掩码，避免采样数学再反向依赖持有世界对象的后处理门面。
final class PathSplineSampler {
    private PathSplineSampler() { }

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
        // 只向前搜索原32段窗口；立交第二次经过同一X/Z时不能投回已走过的低处道路。
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

}
