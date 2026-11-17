/* 文件职责：巨树（Tan's Huge Trees）的精确列预留：在 TreePlacer 写方块之前调用，避免树与路抢同一列。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** 巨树写入前预留精确列，避免异步道路规划与树放置竞态；装饰模板不按普通巨树预留。
 *
 * Exact-column reservation for normal Tan's Huge Trees.  The optional tiny
 * compatibility hook calls this before TreePlacer writes blocks, eliminating
 * the race with asynchronous road planning without treating decoration
 * templates as protected trees.
 */
public final class HugeTreeReservation {
    private static final String SHAPE_CLASS = "tannyjung.tanshugetrees_handcode.systems.Caches$TreeShape";
    private static final int MAX_RESERVED_CHUNKS_PER_LEVEL = 4096;
    private static final Map<ServerLevel, LevelReservations> RESERVATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Method shapeMethod;

    private HugeTreeReservation() {
    }

    public static void reserve(ServerLevel level, String template, BlockPos center, int[] rotationMirrored, int fallenDirection) {
        if (level == null || center == null || template == null || template.isBlank() || !isRoadTree(template)) return;
        short[] shape = shape(template);
        if (shape.length < 4) return;
        int rotation = rotationMirrored != null && rotationMirrored.length > 0 ? rotationMirrored[0] : 1;
        int mirrored = rotationMirrored != null && rotationMirrored.length > 1 ? rotationMirrored[1] : 0;
        // 先算形状再一次发布，不让规划线程看到同一棵树只预留了一半。
        LongArrayList columns = new LongArrayList();
        for (int i = 0; i + 3 < shape.length; i += 4) {
            int type = Math.abs(shape[i]);
            if (type < 1100 || type >= 1200) continue;
            int[] offset = rotateMirror(shape[i + 1], shape[i + 2], shape[i + 3], rotation, mirrored);
            offset = fallen(offset[0], offset[1], offset[2], fallenDirection);
            columns.add(BlockPos.asLong(center.getX() + offset[0], 0, center.getZ() + offset[2]));
        }
        if (!columns.isEmpty()) RESERVATIONS.computeIfAbsent(level, ignored -> new LevelReservations()).reserveAll(columns);
    }

    public static boolean isReserved(ServerLevel level, BlockPos pos) {
        return pos != null && isReserved(level, pos.getX(), pos.getZ());
    }

    /** 预留按 X/Z；终态体积已具备列坐标，查询不用再构造方块坐标。 */
    public static boolean isReserved(ServerLevel level, int x, int z) {
        if (level == null) return false;
        LevelReservations reservations = RESERVATIONS.get(level);
        return reservations != null && reservations.contains(x, z);
    }

    /** 只在一次寻路开始时取视图，避免在格点、跨边和弹性带热循环中查全局锁。 */
    public static PlanningMask planningMask(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int radius) {
        LevelReservations reservations = level == null ? null : RESERVATIONS.get(level);
        return reservations == null ? PlanningMask.EMPTY : reservations.planningMask(minX, minZ, maxX, maxZ, radius);
    }

    /** 道路特征完成后仅释放对应区块列，避免提早释放邻区块占地。 */
    public static void releaseChunk(ServerLevel level, ChunkPos chunk) {
        if (level == null || chunk == null) return;
        LevelReservations reservations = RESERVATIONS.get(level);
        // 保留活跃维度的空容器，防止释放最后一块时把另一个线程刚取得的写入容器摘掉。
        if (reservations != null) reservations.release(chunk.toLong());
    }

    public static void clearForLevel(ServerLevel level) {
        if (level == null) return;
        synchronized (RESERVATIONS) { RESERVATIONS.remove(level); }
    }

    public static void clearAll() {
        synchronized (RESERVATIONS) { RESERVATIONS.clear(); }
    }

    private static boolean isRoadTree(String template) {
        String value = template.replace('\\', '/').toLowerCase(Locale.ROOT);
        return !value.contains("/#nature_stuffs/") && !value.contains("/rock/") && !value.contains("/rock_");
    }

    private static short[] shape(String template) {
        try {
            Method method = shapeMethod;
            if (method == null) {
                synchronized (HugeTreeReservation.class) {
                    method = shapeMethod;
                    if (method == null) {
                        method = Class.forName(SHAPE_CLASS).getMethod("getTreeShapeData", String.class);
                        shapeMethod = method;
                    }
                }
            }
            Object result = method.invoke(null, template);
            return result instanceof short[] values ? values : new short[0];
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return new short[0];
        }
    }

    private static int[] rotateMirror(int x, int y, int z, int rotation, int mirrored) {
        int rx = x, rz = z;
        if (rotation == 2) { rx = z; rz = -x; }
        else if (rotation == 3) { rx = -x; rz = -z; }
        else if (rotation == 4) { rx = -z; rz = x; }
        if (mirrored == 1) rx = -rx;
        else if (mirrored == 2) rz = -rz;
        return new int[]{rx, y, rz};
    }

    private static int[] fallen(int x, int y, int z, int direction) {
        return switch (direction) {
            case 1 -> new int[]{y, x, z};
            case 2 -> new int[]{x, z, y};
            case 3 -> new int[]{-y, x, z};
            case 4 -> new int[]{x, z, -y};
            default -> new int[]{x, y, z};
        };
    }

    static final class LevelReservations {
        // 一份区块索引同时负责列查询和淘汰，避免双索引与陈旧 FIFO 在释放/重入后分叉。
        private final Long2ObjectLinkedOpenHashMap<BitSet> byChunk = new Long2ObjectLinkedOpenHashMap<>();

        synchronized void reserveAll(LongArrayList columns) {
            for (int i = 0; i < columns.size(); i++) {
                long column = columns.getLong(i);
                int x = BlockPos.getX(column), z = BlockPos.getZ(column);
                long key = ChunkPos.asLong(x >> 4, z >> 4);
                BitSet bits = byChunk.get(key);
                if (bits == null) {
                    bits = new BitSet(256);
                    byChunk.put(key, bits);
                }
                bits.set(localIndex(x, z));
            }
            while (byChunk.size() > MAX_RESERVED_CHUNKS_PER_LEVEL) byChunk.removeFirst();
        }

        synchronized void release(long chunkKey) {
            byChunk.remove(chunkKey);
        }

        synchronized boolean contains(int x, int z) {
            BitSet bits = byChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            return bits != null && bits.get(localIndex(x, z));
        }

        PlanningMask planningMask(int minX, int minZ, int maxX, int maxZ, int radius) {
            int r = Math.max(0, radius);
            var buckets = copyChunks((minX - r) >> 4, (minZ - r) >> 4, (maxX + r) >> 4, (maxZ + r) >> 4);
            return buckets == null ? PlanningMask.EMPTY : PlanningMask.project(buckets, minX, minZ, maxX, maxZ, r);
        }

        private synchronized Long2ObjectOpenHashMap<BitSet> copyChunks(int minX, int minZ, int maxX, int maxZ) {
            if (byChunk.isEmpty()) return null;
            Long2ObjectOpenHashMap<BitSet> result = new Long2ObjectOpenHashMap<>();
            // 小窗口查区块键，大窗口遍历已有桶；空间扩张移到锁外。
            if ((maxX - minX + 1L) * (maxZ - minZ + 1L) <= byChunk.size()) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        long key = ChunkPos.asLong(x, z);
                        BitSet bits = byChunk.get(key);
                        if (bits != null) result.put(key, (BitSet) bits.clone());
                    }
                }
            } else {
                for (var entry : byChunk.long2ObjectEntrySet()) {
                    long key = entry.getLongKey();
                    int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
                    if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                        result.put(key, (BitSet) entry.getValue().clone());
                    }
                }
            }
            return result.isEmpty() ? null : result;
        }
    }

    private static int localIndex(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    /** 寻路独享的不可变列掩码；施工仍读实时预留，因为求解后可能有新树加入。 */
    public static final class PlanningMask {
        private static final PlanningMask EMPTY = new PlanningMask(new Long2ObjectOpenHashMap<>(), 0, 0, -1, -1);
        private final Long2ObjectOpenHashMap<BitSet> chunks;
        private final int minX, minZ, maxX, maxZ;

        private PlanningMask(Long2ObjectOpenHashMap<BitSet> chunks, int minX, int minZ, int maxX, int maxZ) {
            this.chunks = chunks;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        private static PlanningMask project(Long2ObjectOpenHashMap<BitSet> source,
                                            int minX, int minZ, int maxX, int maxZ, int radius) {
            int[] reach = new int[radius + 1];
            for (int dz = 0; dz <= radius; dz++) {
                reach[dz] = (int) Math.sqrt((long) radius * radius - (long) dz * dz);
            }
            Long2ObjectOpenHashMap<BitSet> result = new Long2ObjectOpenHashMap<>();
            int loX = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;
            for (var entry : source.long2ObjectEntrySet()) {
                int chunkX = ChunkPos.getX(entry.getLongKey()) << 4;
                int chunkZ = ChunkPos.getZ(entry.getLongKey()) << 4;
                BitSet bits = entry.getValue();
                // 连续树列先合成一段再外扩，粗格点查询不用逐次重算道路半径。
                for (int bit = bits.nextSetBit(0); bit >= 0;) {
                    int end = Math.min(bits.nextClearBit(bit), (bit | 15) + 1);
                    int fromX = chunkX + (bit & 15), toX = fromX + end - bit - 1;
                    int centerZ = chunkZ + (bit >> 4);
                    for (int z = Math.max(minZ, centerZ - radius); z <= Math.min(maxZ, centerZ + radius); z++) {
                        int dx = reach[Math.abs(z - centerZ)];
                        int from = Math.max(minX, fromX - dx), to = Math.min(maxX, toX + dx);
                        if (from > to) continue;
                        setRange(result, from, to, z);
                        loX = Math.min(loX, from);
                        hiX = Math.max(hiX, to);
                        loZ = Math.min(loZ, z);
                        hiZ = Math.max(hiZ, z);
                    }
                    bit = bits.nextSetBit(end);
                }
            }
            return result.isEmpty() ? EMPTY : new PlanningMask(result, loX, loZ, hiX, hiZ);
        }

        private static void setRange(Long2ObjectOpenHashMap<BitSet> chunks, int from, int to, int z) {
            for (int x = from; x <= to;) {
                int end = Math.min(to, x | 15);
                long key = ChunkPos.asLong(x >> 4, z >> 4);
                chunks.computeIfAbsent(key, ignored -> new BitSet(256))
                        .set(localIndex(x, z), localIndex(end, z) + 1);
                x = end + 1;
            }
        }

        public boolean isEmpty() {
            return chunks.isEmpty();
        }

        public boolean blocks(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
            BitSet bits = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
            return bits != null && bits.get(localIndex(x, z));
        }

        /** 逐列覆盖而非固定取三个点；共角时两侧都查，跨粗格的单列不能漏。 */
        public boolean intersects(int x, int z, int endX, int endZ) {
            if (isEmpty() || Math.max(x, endX) < minX || Math.min(x, endX) > maxX
                    || Math.max(z, endZ) < minZ || Math.min(z, endZ) > maxZ) return false;
            int sx = Integer.compare(endX, x), sz = Integer.compare(endZ, z);
            long dx = Math.abs((long) endX - x), dz = Math.abs((long) endZ - z);
            for (long nextX = 1, nextZ = 1; ; ) {
                if (blocks(x, z)) return true;
                if (x == endX && z == endZ) return false;
                int order = x == endX ? 1 : z == endZ ? -1 : Long.compare(nextX * dz, nextZ * dx);
                if (order == 0 && (blocks(x + sx, z) || blocks(x, z + sz))) return true;
                if (order <= 0) {
                    x += sx;
                    nextX += 2;
                }
                if (order >= 0) {
                    z += sz;
                    nextZ += 2;
                }
            }
        }
    }
}
