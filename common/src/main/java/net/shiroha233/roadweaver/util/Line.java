/* 文件职责：线段几何计算，提供最近点投影和 Frenet 标架。 */
package net.shiroha233.roadweaver.util;

import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 线段几何计算，提供最近点投影和 Frenet 标架
 */
public class Line {

    private final Vec3 start;
    private final Vec3 end;
    private final double horizontalBend;

    public Line(Vec3 start, Vec3 end) {
        this(start, end, 0.0D);
    }

    private Line(Vec3 start, Vec3 end, double horizontalBend) {
        this.start = start;
        this.end = end;
        this.horizontalBend = horizontalBend;
    }

    public static Line withHorizontalBend(Vec3 start, Vec3 end, double offset) {
        return new Line(start, end, offset);
    }

    public boolean isCurved() {
        return Math.abs(horizontalBend) > 1.0E-6D && horizontalLength() > 1.0E-6D;
    }

    private double horizontalLength() {
        return Math.sqrt((end.x - start.x) * (end.x - start.x)
                + (end.z - start.z) * (end.z - start.z));
    }

    private Vec3 chordPoint(double t) {
        return start.lerp(end, Math.max(0.0D, Math.min(1.0D, t)));
    }

    /** 桥中心线侧偏在两端归零，保留长桥正弦而不移动桥头。 */
    public Vec3 pointAt(double t) {
        t = Math.max(0.0D, Math.min(1.0D, t));
        Vec3 point = chordPoint(t);
        if (!isCurved()) return point;
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        double nx = -dz / length;
        double nz = dx / length;
        double offset = horizontalBend * Math.sin(Math.PI * t);
        return new Vec3(point.x + nx * offset, point.y, point.z + nz * offset);
    }

    /** 将弦线占地列映射到曲桥中心线，保护与写入须消费同一几何。 */
    public BlockPos mapRoadPosition(BlockPos original) {
        if (original == null || !isCurved()) return original;
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double lenSq = dx * dx + dz * dz;
        if (lenSq <= 1.0E-6D) return original;
        double px = original.getX() + 0.5D - start.x;
        double pz = original.getZ() + 0.5D - start.z;
        double t = Math.max(0.0D, Math.min(1.0D, (px * dx + pz * dz) / lenSq));
        Vec3 chord = chordPoint(t);
        Vec3 curved = pointAt(t);
        double lateralX = (original.getX() + 0.5D) - chord.x;
        double lateralZ = (original.getZ() + 0.5D) - chord.z;
        return new BlockPos((int) Math.floor(curved.x + lateralX), original.getY(),
                (int) Math.floor(curved.z + lateralZ));
    }

    public List<BlockPos> mapRoadPositions(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty() || !isCurved()) return positions;
        List<BlockPos> mapped = new ArrayList<>(positions.size());
        for (BlockPos position : positions) mapped.add(mapRoadPosition(position));
        return mapped;
    }

    public static class Frame {
        public Vec3 closestPoint;
        public Vec3 tangent;
        public double globalT;
        public Vec3 tangent0;
        public Vec3 normal0;
        public Vec3 binormal0;

        @Override
        public String toString() {
            return String.format("Closet: %s\nTangent: %s\nGlobal t: %.4f", closestPoint, tangent, globalT);
        }
    }

    public Frame getFrame(Vec3 testPoint) {
        if (isCurved()) return getCurvedFrame(testPoint);
        Vec3 direction = end.subtract(start);

        if (direction.length() == 0) {
            Frame res = new Frame();
            res.closestPoint = start;
            res.tangent = new Vec3(0, 0, 0);
            res.globalT = 0;
            res.tangent0 = new Vec3(0, 0, 0);
            res.normal0 = new Vec3(0, 1, 0);
            res.binormal0 = new Vec3(0, 0, 0);
            return res;
        }

        Vec3 tangent = direction.normalize();
        Vec3 lineToTest = testPoint.subtract(start);
        double t = lineToTest.dot(tangent);

        Vec3 closestPoint;
        if (t <= 0) {
            closestPoint = start;
        } else if (t >= direction.length()) {
            closestPoint = end;
        } else {
            closestPoint = start.add(tangent.scale(t));
        }

        Frame res = new Frame();
        res.closestPoint = closestPoint;
        res.tangent = tangent;
        res.globalT = t / direction.length();
        res.tangent0 = new Vec3(res.tangent.x, 0, res.tangent.z).normalize();
        res.normal0 = new Vec3(0, 1, 0);
        res.binormal0 = res.tangent0.cross(res.normal0).normalize();

        return res;
    }

    private Frame getCurvedFrame(Vec3 testPoint) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double length = Math.hypot(dx, dz);
        double t = closestCurvedParameter(testPoint, dx, dy, dz, length);
        Vec3 point = pointAt(t);
        double tangentX = dx;
        double tangentZ = dz;
        if (length > 1.0E-6D) {
            tangentX += (-dz / length) * horizontalBend * Math.PI * Math.cos(Math.PI * t);
            tangentZ += (dx / length) * horizontalBend * Math.PI * Math.cos(Math.PI * t);
        }
        Vec3 tangent = new Vec3(tangentX, dy, tangentZ).normalize();
        Vec3 tangent0 = new Vec3(tangent.x, 0, tangent.z);
        if (tangent0.lengthSqr() < 1.0E-8D) tangent0 = new Vec3(1, 0, 0);
        tangent0 = tangent0.normalize();
        Frame res = new Frame();
        res.closestPoint = point;
        res.tangent = tangent;
        res.globalT = t;
        res.tangent0 = tangent0;
        res.normal0 = new Vec3(0, 1, 0);
        res.binormal0 = tangent0.cross(res.normal0).normalize();
        return res;
    }

    private double closestCurvedParameter(Vec3 query, double dx, double dy, double dz, double horizontal) {
        double x = query.x - start.x, y = query.y - start.y, z = query.z - start.z;
        double chordSq = dx * dx + dy * dy + dz * dz;
        double t0 = (x * dx + y * dy + z * dz) / chordSq;
        double lateral = (-x * dz + z * dx) / horizontal;
        double bend = horizontalBend;
        double frequency = Math.PI;
        // 正弦偏移与弦垂直。距离平方导数的一半为 chordSq*(t-t0) 加一个有界修正，
        // 以此把根夹在弦投影附近，长桥无需在整条跨度上反复采点。
        double correctionBound = Math.abs(bend) * frequency * (Math.abs(bend) + Math.abs(lateral));
        double margin = correctionBound / chordSq;
        double lo = Math.clamp(t0 - margin, 0.0D, 1.0D);
        double hi = Math.clamp(t0 + margin, 0.0D, 1.0D);
        boolean convex = chordSq > correctionBound * frequency;
        double speedBound = Math.sqrt(chordSq) + Math.abs(bend) * frequency;
        // 投影误差按方块而非 t 衡量，否则 1300 格桥用固定迭代会错开数格，被两格轴向门整列拒绝。
        while ((hi - lo) * speedBound > 1.0E-5D && Math.nextUp(lo) < hi) {
            if (convex) {
                double mid = lo + (hi - lo) * 0.5D;
                double phase = frequency * mid;
                double slope = chordSq * (mid - t0)
                        + bend * frequency * Math.cos(phase) * (bend * Math.sin(phase) - lateral);
                if (slope > 0.0D) hi = mid;
                else lo = mid;
            } else {
                // 离桥很远的查询未必满足凸性界，仍按实际三维距离在有界区间收敛。
                double a = lo + (hi - lo) / 3.0D;
                double b = hi - (hi - lo) / 3.0D;
                if (pointAt(a).distanceToSqr(query) <= pointAt(b).distanceToSqr(query)) hi = b;
                else lo = a;
            }
        }
        return lo + (hi - lo) * 0.5D;
    }

    public double getTotalLength() {
        if (isCurved()) {
            double length = 0.0D;
            Vec3 previous = pointAt(0.0D);
            for (int i = 1; i <= 32; i++) {
                Vec3 current = pointAt(i / 32.0D);
                length += previous.distanceTo(current);
                previous = current;
            }
            return length;
        }
        return start.distanceTo(end);
    }
}
