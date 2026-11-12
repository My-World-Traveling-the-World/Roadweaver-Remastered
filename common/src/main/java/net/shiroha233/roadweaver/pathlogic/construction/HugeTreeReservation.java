/* 文件职责：巨树（Tan's Huge Trees）的精确列预留：在 TreePlacer 写方块之前调用，避免树与路抢同一列。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
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
        LevelReservations reservations;
        synchronized (RESERVATIONS) {
            reservations = RESERVATIONS.computeIfAbsent(level, ignored -> new LevelReservations());
        }
        for (int i = 0; i + 3 < shape.length; i += 4) {
            int type = Math.abs(shape[i]);
            if (type < 1100 || type >= 1200) continue;
            int[] offset = rotateMirror(shape[i + 1], shape[i + 2], shape[i + 3], rotation, mirrored);
            offset = fallen(offset[0], offset[1], offset[2], fallenDirection);
            reservations.reserve(center.getX() + offset[0], center.getZ() + offset[2]);
        }
        reservations.evictOverflow();
    }

    public static boolean isReserved(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        LevelReservations reservations;
        synchronized (RESERVATIONS) { reservations = RESERVATIONS.get(level); }
        return reservations != null && reservations.contains(pos.getX(), pos.getZ());
    }

    /** Releases only columns whose own chunk has finished the road feature. */
    public static void releaseChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
        if (level == null || chunk == null) return;
        LevelReservations reservations;
        synchronized (RESERVATIONS) { reservations = RESERVATIONS.get(level); }
        if (reservations == null) return;
        reservations.release(net.minecraft.world.level.ChunkPos.asLong(chunk.x, chunk.z));
        if (reservations.byChunk.isEmpty()) {
            synchronized (RESERVATIONS) {
                if (reservations.byChunk.isEmpty()) RESERVATIONS.remove(level);
            }
        }
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
        private final Set<Long> columns = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<Long, Set<Long>> byChunk = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> insertionOrder = new ConcurrentLinkedQueue<>();

        void reserve(int x, int z) {
            long chunkKey = net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4);
            long columnKey = BlockPos.asLong(x, 0, z);
            Set<Long> chunkColumns = byChunk.computeIfAbsent(chunkKey, key -> {
                insertionOrder.add(key);
                return ConcurrentHashMap.newKeySet();
            });
            chunkColumns.add(columnKey);
            columns.add(columnKey);
        }

        void release(long chunkKey) {
            Set<Long> removed = byChunk.remove(chunkKey);
            if (removed != null) columns.removeAll(removed);
        }

        boolean contains(int x, int z) {
            return columns.contains(BlockPos.asLong(x, 0, z));
        }

        int chunkCount() {
            return byChunk.size();
        }

        void evictOverflow() {
            while (byChunk.size() > MAX_RESERVED_CHUNKS_PER_LEVEL) {
                Long oldest = insertionOrder.poll();
                if (oldest == null) return;
                release(oldest);
            }
        }
    }
}
