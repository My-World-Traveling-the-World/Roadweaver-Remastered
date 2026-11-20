package net.shiroha233.roadweaver.pathlogic.pathcalc;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;

/** 相同稳定假世界对照动态读路径与主线程稳定窗口；读次数是证据，耗时不代表真实世界成本。 */
public final class HeightWindowProbe {
    private static volatile Object retained;
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var points = new ArrayList<BlockPos>();
        for (int i = 0; i < 4096; i++) points.add(new BlockPos(-16 + i % 16, 70, -1));
        var reads = new AtomicInteger();
        WorldGenLevel world = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (p, method, values) -> {
                    if (!method.getName().equals("getHeight")) throw new AssertionError(method.getName());
                    reads.incrementAndGet(); return height((int) values[1], (int) values[2]);
                });
        var chunk = new ChunkPos(-1, -1);
        var old = HeightProfileService.build(world, points, chunk, 8, true, 3, List.of());
        int oldReads = reads.getAndSet(0);
        var next = HeightProfileService.buildStable(points, chunk, 8, true, 3, (x,z) -> {
            reads.incrementAndGet(); return height(x,z);
        });
        if (!Arrays.equals(old.smoothedY(), next.smoothedY())) throw new AssertionError("高度产物不等价");
        int newReads = reads.get();
        for (int i = 0; i < 3; i++) { measure(world, points, chunk, false); measure(world, points, chunk, true); }
        double[] before = new double[9], after = new double[9];
        for (int i = 0; i < 9; i++) {
            if ((i & 1) == 0) { before[i] = measure(world,points,chunk,false); after[i] = measure(world,points,chunk,true); }
            else { after[i] = measure(world,points,chunk,true); before[i] = measure(world,points,chunk,false); }
        }
        Arrays.sort(before); Arrays.sort(after);
        String json = "{\"unit\":\"milliseconds_per_16_profiles\",\"fixture\":\"stable-fake-height-reader\","
                + "\"old_reads_per_profile\":" + oldReads + ",\"new_reads_per_profile\":" + newReads
                + ",\"old_ms_p50\":" + before[4] + ",\"new_ms_p50\":" + after[4] + "}\n";
        Files.writeString(Path.of(args[0]), json); System.out.print(json);
    }
    private static double measure(WorldGenLevel world, List<BlockPos> points, ChunkPos chunk, boolean current) {
        long start = System.nanoTime();
        for (int i = 0; i < 16; i++) retained = current
                ? HeightProfileService.buildStable(points, chunk, 8, true, 3,
                    (x, z) -> world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z))
                : HeightProfileService.build(world, points, chunk, 8, true, 3, List.of());
        return (System.nanoTime() - start) / 1e6;
    }
    private static int height(int x, int z) { return 40 + Math.floorMod(x * 7 + z * 11, 60); }
}
