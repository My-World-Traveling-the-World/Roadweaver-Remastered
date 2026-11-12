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

    /** Point on the bridge centerline. The lateral offset is zero at both ends. */
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

    /** Maps a straight-chord footprint column onto this bridge centerline. */
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
        double dz = end.z - start.z;
        double lenSq = dx * dx + dz * dz;
        double initial = lenSq <= 1.0E-6D ? 0.0D
                : ((testPoint.x - start.x) * dx + (testPoint.z - start.z) * dz) / lenSq;
        double lo = Math.max(0.0D, initial - 0.20D);
        double hi = Math.min(1.0D, initial + 0.20D);
        for (int i = 0; i < 8; i++) {
            double a = lo + (hi - lo) / 3.0D;
            double b = hi - (hi - lo) / 3.0D;
            if (pointAt(a).distanceToSqr(testPoint) <= pointAt(b).distanceToSqr(testPoint)) hi = b;
            else lo = a;
        }
        double t = (lo + hi) * 0.5D;
        Vec3 point = pointAt(t);
        double tangentX = dx;
        double tangentZ = dz;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > 1.0E-6D) {
            tangentX += (-dz / length) * horizontalBend * Math.PI * Math.cos(Math.PI * t);
            tangentZ += (dx / length) * horizontalBend * Math.PI * Math.cos(Math.PI * t);
        }
        Vec3 tangent = new Vec3(tangentX, end.y - start.y, tangentZ).normalize();
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
