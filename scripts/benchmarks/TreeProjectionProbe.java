package net.shiroha233.roadweaver.pathlogic.construction;

import com.sun.management.ThreadMXBean;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;

/** 比较旧逐体素投影和当前投影加真实区块索引登记；不包括世界读取、反射和游戏调度。 */
public class TreeProjectionProbe {
    private static final BlockPos CENTER = new BlockPos(-17, 64, -1);
    private static volatile Object retained;
    private static final ThreadMXBean MEMORY = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int ITERATIONS = 128;

    public static void main(String[] args) throws Exception {
        String[] names = {"tall-duplicate", "layered-trunk", "unique-columns"};
        short[][] shapes = {shape(1, 1, 4096), shape(32, 32, 8), shape(64, 64, 1)};
        StringBuilder json = new StringBuilder("{\"scope\":\"projection-and-fresh-index-publication\","
                + "\"timing_unit\":\"milliseconds_per_128_operations\","
                + "\"allocation_unit\":\"thread_allocated_bytes_per_operation\","
                + "\"iterations\":128,\"warmups\":5,\"samples\":9,\"cases\":[");
        for (int c = 0; c < shapes.length; c++) {
            short[] shape = shapes[c];
            var oldColumns = frozen(shape);
            var current = TreeColumnProjection.project(shape, CENTER, null, -1);
            if (new LinkedHashSet<>(oldColumns).size() != current.columns())
                throw new AssertionError("占地列计数不等价");
            var oldIndex = new HugeTreeReservation.LevelReservations();
            var newIndex = new HugeTreeReservation.LevelReservations();
            oldIndex.reserveAll(oldColumns);
            newIndex.reserve(current);
            for (int x = -20; x < 50; x++) for (int z = -5; z < 70; z++)
                if (oldIndex.contains(x, z) != newIndex.contains(x, z)) throw new AssertionError("真实索引不等价");
            for (int warmup = 0; warmup < 5; warmup++) { measure(shape, false); measure(shape, true); }
            double[][] old = new double[9][], next = new double[9][];
            for (int i = 0; i < 9; i++) {
                if ((i & 1) == 0) { old[i] = measure(shape, false); next[i] = measure(shape, true); }
                else { next[i] = measure(shape, true); old[i] = measure(shape, false); }
            }
            if (c > 0) json.append(',');
            json.append("{\"name\":\"").append(names[c]).append("\",\"voxels\":").append(shape.length / 4)
                    .append(",\"old_publications\":").append(oldColumns.size())
                    .append(",\"new_columns\":").append(current.columns())
                    .append(",\"new_chunk_merges\":").append(current.chunks().size())
                    .append(",\"old_ms_p50\":").append(median(old, 0)).append(",\"new_ms_p50\":").append(median(next, 0))
                    .append(",\"old_allocated_bytes_per_operation_p50\":").append(median(old, 1))
                    .append(",\"new_allocated_bytes_per_operation_p50\":").append(median(next, 1)).append('}');
        }
        json.append("]}\n");
        Files.writeString(Path.of(args[0]), json);
        System.out.print(json);
    }

    private static double[] measure(short[] shape, boolean current) {
        long allocated = MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long started = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            var index = new HugeTreeReservation.LevelReservations();
            if (current) index.reserve(TreeColumnProjection.project(shape, CENTER, null, -1));
            else index.reserveAll(frozen(shape));
            retained = index;
        }
        return new double[]{(System.nanoTime() - started) / 1_000_000.0,
                (MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated) / (double) ITERATIONS};
    }
    private static double median(double[][] values, int column) {
        double[] sorted = Arrays.stream(values).mapToDouble(row -> row[column]).sorted().toArray();
        return sorted[sorted.length / 2];
    }
    private static short[] shape(int width, int depth, int height) {
        short[] shape = new short[width * depth * height * 4];
        int i = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            shape[i++] = 1143; shape[i++] = (short) x; shape[i++] = (short) y; shape[i++] = (short) z;
        }
        return shape;
    }
    private static LongArrayList frozen(short[] shape) {
        var columns = new LongArrayList();
        for (int i = 0; i + 3 < shape.length; i += 4) {
            int type = Math.abs((int) shape[i]);
            if (type < 1100 || type >= 1200) continue;
            // 原实现默认旋转、镜像及未倒伏的两份中间数组；JIT可自行决定消除它们。
            int[] rotated = {shape[i + 1], shape[i + 2], shape[i + 3]};
            int[] fallen = {rotated[0], rotated[1], rotated[2]};
            columns.add(BlockPos.asLong(CENTER.getX() + fallen[0], 0, CENTER.getZ() + fallen[2]));
        }
        return columns;
    }
}
