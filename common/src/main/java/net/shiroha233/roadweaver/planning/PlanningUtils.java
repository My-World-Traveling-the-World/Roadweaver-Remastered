package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;

/**
 * 路径规划相关的工具方法集合
 */
public final class PlanningUtils {
    private PlanningUtils() {}

    public static long pos2dKey(BlockPos p) {
        long x = p.getX();
        long z = p.getZ();
        return (x << 32) ^ (z & 0xffffffffL);
    }

    public static BlockPos posFrom2dKey(long key) {
        return new BlockPos((int) (key >> 32), 0, (int) key);
    }

    public static long edgeKey(BlockPos a, BlockPos b) {
        long ka = pos2dKey(a);
        long kb = pos2dKey(b);
        return edgeKey(ka, kb);
    }

    public static long edgeKey(long ka, long kb) {
        long lo = Math.min(ka, kb);
        long hi = Math.max(ka, kb);
        long mixedLo = mix64(lo ^ 0x9E3779B97F4A7C15L);
        long mixedHi = mix64(hi ^ 0xC2B2AE3D27D4EB4FL);
        return mix64(mixedLo ^ Long.rotateLeft(mixedHi, 29));
    }

    private static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    public static boolean sameEdge(StructureConnection a, StructureConnection b) {
        BlockPos af = a.from(), at = a.to();
        BlockPos bf = b.from(), bt = b.to();
        return (af.equals(bf) && at.equals(bt)) || (af.equals(bt) && at.equals(bf));
    }

    public static boolean sameEdge(StructureConnection c, BlockPos a, BlockPos b) {
        BlockPos cf = c.from();
        BlockPos ct = c.to();
        return (cf.equals(a) && ct.equals(b)) || (cf.equals(b) && ct.equals(a));
    }
}
