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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("RoadWeaver/TreeReservation");
    private static final java.util.concurrent.atomic.AtomicBoolean SHAPE_FAILURE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 状态只用于当次诊断，不写入存档；无需预留与读取失败不能混成同一个成功。 */
    public enum ReservationStatus { RESERVED, INVALID_INPUT, IGNORED_TEMPLATE, SHAPE_UNAVAILABLE, NO_TRUNK_COLUMNS }
    public record ReservationResult(ReservationStatus status, int columns) {
        public boolean reserved() { return status == ReservationStatus.RESERVED; }
    }

    private HugeTreeReservation() {
    }

    public static void reserve(ServerLevel level, String template, BlockPos center, int[] rotationMirrored, int fallenDirection) {
        // 保留已有void入口供旧二进制调用；公开API使用带结果入口，不能凭调用未抛错报成功。
        reserveResult(level, template, center, rotationMirrored, fallenDirection);
    }

    public static ReservationResult reserveResult(ServerLevel level, String template, BlockPos center,
            int[] rotationMirrored, int fallenDirection) {
        if (level == null || center == null || template == null || template.isBlank())
            return new ReservationResult(ReservationStatus.INVALID_INPUT, 0);
        if (!isRoadTree(template)) return new ReservationResult(ReservationStatus.IGNORED_TEMPLATE, 0);
        short[] shape;
        try {
            shape = shape(template);
            if (shape == null || shape.length == 0 || shape.length % 4 != 0)
                throw new IllegalStateException("巨树形状为空或四元组不完整");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            // 故障必须留下可辨识状态和一次告警；不能伪装成模板正常没有树干。
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException wrapped
                    && wrapped.getCause() != null ? wrapped.getCause() : failure;
            if (SHAPE_FAILURE_LOGGED.compareAndSet(false, true))
                LOGGER.warn("【巨树占地读取失败，本次未发布保护列】 TREE_RESERVATION_FAILED template={} failure={}",
                        template, cause.toString());
            return new ReservationResult(ReservationStatus.SHAPE_UNAVAILABLE, 0);
        }
        // 先算形状再一次发布，不让规划线程看到同一棵树只预留了一半。
        var projection = TreeColumnProjection.project(shape, center, rotationMirrored, fallenDirection);
        if (projection.columns() == 0) return new ReservationResult(ReservationStatus.NO_TRUNK_COLUMNS, 0);
        RESERVATIONS.computeIfAbsent(level, ignored -> new LevelReservations()).reserve(projection);
        return new ReservationResult(ReservationStatus.RESERVED, projection.columns());
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
        SHAPE_FAILURE_LOGGED.set(false);
    }

    private static boolean isRoadTree(String template) {
        String value = template.replace('\\', '/').toLowerCase(Locale.ROOT);
        return !value.contains("/#nature_stuffs/") && !value.contains("/rock/") && !value.contains("/rock_");
    }

    private static short[] shape(String template) throws ReflectiveOperationException {
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
        if (!(result instanceof short[] values)) throw new IllegalStateException("巨树形状返回类型不匹配");
        return values;
    }

    static final class LevelReservations {
        // 一份区块索引同时负责列查询和淘汰，避免双索引与陈旧 FIFO 在释放/重入后分叉。
        private final Long2ObjectLinkedOpenHashMap<BitSet> byChunk = new Long2ObjectLinkedOpenHashMap<>();

        /** 一次合并每区块的四个字，发布不再逐列重复拆坐标、查表和设置同一位。 */
        synchronized void reserve(TreeColumnProjection.Projection projection) {
            for (var entry : projection.chunks().long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                BitSet added = BitSet.valueOf(entry.getValue());
                BitSet existing = byChunk.get(key);
                if (existing == null) byChunk.put(key, added);
                else existing.or(added);
            }
            // 按首次出现的区块顺序合并再淘汰，与旧逐列登记的保留集合相同。
            while (byChunk.size() > MAX_RESERVED_CHUNKS_PER_LEVEL) byChunk.removeFirst();
        }

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
