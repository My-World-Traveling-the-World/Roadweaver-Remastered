/* 文件职责：不重复遍历相邻道路区块共享的缓存刷新范围。 */
package net.shiroha233.roadweaver.worldgen.road;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collection;
import java.util.function.LongConsumer;
import net.minecraft.world.level.ChunkPos;

final class ChunkPlanNeighborhood {
    private ChunkPlanNeighborhood() {}

    static void visit(Collection<Long> keys, int radius, LongConsumer visit) {
        if (keys.isEmpty()) return;
        if (keys.size() == 1) {
            expand(keys.iterator().next(), radius, false, false, visit);
            return;
        }
        var centers = new LongOpenHashSet(keys);
        int minX=Integer.MAX_VALUE, maxX=Integer.MIN_VALUE, minZ=Integer.MAX_VALUE, maxZ=Integer.MIN_VALUE;
        for (var it = centers.iterator(); it.hasNext();) {
            long key = it.nextLong();
            int x=ChunkPos.getX(key), z=ChunkPos.getZ(key);
            minX=Math.min(minX,x); maxX=Math.max(maxX,x);
            minZ=Math.min(minZ,z); maxZ=Math.max(maxZ,z);
        }
        long width = (long)maxX-minX+1, height = (long)maxZ-minZ+1;
        // 唯一键数等于包围盒面积才是实心矩形；整条直路也是宽或高为一的实心矩形。
        if (width <= centers.size() && height <= centers.size() && width*height == centers.size()) {
            for (long z=(long)minZ-radius; z<=(long)maxZ+radius; z++)
                for (long x=(long)minX-radius; x<=(long)maxX+radius; x++)
                    visit.accept(ChunkPos.asLong((int)x,(int)z));
            return;
        }
        for (long key : centers) {
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            // 西邻已覆盖当前方框除东边外的所有列，北邻同理。只依赖邻居存在，与遍历次序无关。
            expand(key, radius, centers.contains(ChunkPos.asLong(x - 1, z)),
                    centers.contains(ChunkPos.asLong(x, z - 1)), visit);
        }
    }

    private static void expand(long key, int r, boolean west, boolean north, LongConsumer visit) {
        int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
        for (int dz = north ? r : -r; dz <= r; dz++)
            for (int dx = west ? r : -r; dx <= r; dx++)
                visit.accept(ChunkPos.asLong(x + dx, z + dz));
    }
}
