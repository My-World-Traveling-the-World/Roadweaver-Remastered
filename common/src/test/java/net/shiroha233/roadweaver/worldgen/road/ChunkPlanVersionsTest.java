package net.shiroha233.roadweaver.worldgen.road;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 缓存容量之外，在途版本还必须保留到真正退出，不能靠清表制造 ABA。 */
class ChunkPlanVersionsTest {
    private static final RoadWorldgenPlanCache.PlanConfig CONFIG = new RoadWorldgenPlanCache.PlanConfig(4, true);
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);

    @Test
    void neighborhoodMatchesEveryReferenceKeyIncludingHolesAndDiagonals() {
        int radius = RoadWorldgenPlanCache.invalidationRadiusChunksForTests();
        var random = new java.util.Random(114);
        for (int trial = 0; trial < 100; trial++) {
            var centers = new ArrayList<Long>();
            for (int i = 0; i <= trial; i++) centers.add(ChunkPos.asLong(random.nextInt(20)-10, random.nextInt(20)-10));
            var expected = new java.util.HashSet<Long>();
            for (long key : centers) for (int dx=-radius; dx<=radius; dx++) for (int dz=-radius; dz<=radius; dz++)
                expected.add(ChunkPos.asLong(ChunkPos.getX(key)+dx, ChunkPos.getZ(key)+dz));
            var actual = new java.util.HashSet<Long>();
            ChunkPlanNeighborhood.visit(centers,radius,actual::add);
            assertEquals(expected,actual);
        }
        // 长直线路段只扫一次重叠带；不能靠重复回调后集合去重冒充减少工作。
        var line = java.util.stream.IntStream.range(0,100).mapToObj(x -> ChunkPos.asLong(x,-7)).toList();
        var calls = new AtomicInteger();
        ChunkPlanNeighborhood.visit(line,radius,k -> calls.incrementAndGet());
        assertEquals((100+2*radius)*(1+2*radius), calls.get());
        // L 形不走实心矩形分支；西边相邻的 99 个中心也不应重复刷新。
        var bent = new ArrayList<>(line);
        bent.add(ChunkPos.asLong(99,-6));
        calls.set(0);
        ChunkPlanNeighborhood.visit(bent,radius,k -> calls.incrementAndGet());
        assertEquals((101+2*radius)*(1+2*radius),calls.get());
    }

    @Test
    void historicalInvalidationsWithoutCachedOrRunningWorkRetainNoKeys() {
        var session = new RoadWorldgenPlanCache.Session();
        var keys = new ArrayList<Long>();
        for (int i = 0; i < 5000; i++) keys.add(ChunkPos.asLong(i * 10, 0));
        session.invalidate(keys);
        assertEquals(0, session.trackedChunks(), "125000 个历史邻区版本不能成为永久驻留项");
    }

    @Test
    void metadataFollowsBothEmptyAndFailedPlanEviction() {
        var session = new RoadWorldgenPlanCache.Session();
        for (int i = 0; i < 17000; i++)
            session.get(new ChunkPos(i, 0), CONFIG, (chunk, config, revision) -> RoadChunkPlan.empty(chunk, 4, revision), () -> 0);
        assertEquals(16384, session.trackedChunks());
        session.invalidate();
        assertEquals(0, session.trackedChunks());
        for (int i = 0; i < 1200; i++)
            session.get(new ChunkPos(i, 0), CONFIG, (chunk, config, revision) ->
                    RoadChunkPlan.unavailable(chunk, 4, revision, RoadChunkPlan.Outcome.RETRYABLE_FAILURE), () -> 0);
        assertEquals(1024, session.trackedChunks());
        session.invalidate();
        assertEquals(0, session.trackedChunks());
    }

    @Test
    void localInvalidationPinsRunningVersionAndRejectsLatePublish() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(() -> session.get(CHUNK, CONFIG, (chunk, config, revision) -> {
                if (calls.incrementAndGet() == 1) { entered.countDown(); await(resume); }
                return RoadChunkPlan.empty(chunk, 4, revision);
            }, () -> 0));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                session.invalidate(List.of(CHUNK.toLong()));
                assertEquals(1, session.trackedChunks(), "失效不能丢掉尚未退出的旧认领");
            } finally { resume.countDown(); }
            var result = pending.get(10, TimeUnit.SECONDS);
            assertTrue(result.revision() > 0);
            assertEquals(2, calls.get(), "旧结果必须重新编译，不能以新版本名义发布");
            assertSame(result, session.get(CHUNK, CONFIG, (a,b,c) -> fail("正确结果已经缓存"), () -> 0));
            session.invalidate(List.of(CHUNK.toLong()));
            assertEquals(0, session.trackedChunks());
        }
    }

    @Test
    void globalInvalidationCannotBeUndoneByAnOlderCompiler() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var old = worker.submit(() -> session.get(CHUNK, CONFIG, (chunk, config, revision) -> {
                entered.countDown(); await(resume); return RoadChunkPlan.empty(chunk, 4, revision);
            }, () -> 0));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            RoadChunkPlan current;
            try {
                session.invalidate();
                current = session.get(CHUNK, CONFIG, (chunk, config, revision) ->
                        RoadChunkPlan.empty(chunk, 4, revision), () -> 0);
                assertTrue(current.revision() > 0);
            } finally { resume.countDown(); }
            assertSame(current, old.get(10, TimeUnit.SECONDS));
            assertEquals(1, session.trackedChunks());
        }
    }

    @Test
    void unrelatedInvalidationKeepsHotPlanAndThrownCompileReleasesClaim() {
        var session = new RoadWorldgenPlanCache.Session();
        var hot = session.get(CHUNK, CONFIG, (chunk, config, revision) -> RoadChunkPlan.empty(chunk, 4, revision), () -> 0);
        session.invalidate(List.of(ChunkPos.asLong(100, 100)));
        assertSame(hot, session.get(CHUNK, CONFIG, (a,b,c) -> fail("局部失效不能迫使无关热点重编译"), () -> 0));
        assertThrows(IllegalStateException.class, () -> session.get(new ChunkPos(-100, 0), CONFIG,
                (a,b,c) -> { throw new IllegalStateException("非正常退出"); }, () -> 0));
        assertEquals(1, session.trackedChunks(), "失败退出不能泄漏额外认领");
    }

    /** 手工微基准只计刷新本身；同一夹具可分别搭配旧版和新版 Session 运行，不把机器耗时设为测试门槛。 */
    public static void main(String[] args) {
        for (String shape : List.of("line", "square", "sparse")) {
            var centers = new ArrayList<Long>();
            for (int i=0; i<100; i++) centers.add(switch (shape) {
                case "line" -> ChunkPos.asLong(i,-7);
                case "square" -> ChunkPos.asLong(i%10-5,i/10-5);
                default -> ChunkPos.asLong(i*6,-7);
            });
            long[] samples = new long[151];
            for (int run=-150; run<samples.length; run++) {
                var session = new RoadWorldgenPlanCache.Session();
                for (long key : centers) session.get(new ChunkPos(key), CONFIG,
                        (p,c,r) -> RoadChunkPlan.empty(p,4,r),() -> 0L);
                long start = System.nanoTime();
                session.invalidate(centers);
                long elapsed = System.nanoTime()-start;
                if (run >= 0) samples[run] = elapsed;
                assertEquals(0,session.trackedChunks());
            }
            java.util.Arrays.sort(samples);
            System.out.printf("[CACHE-BENCH] shape=%s n=%d median_ns=%d%n",shape,samples.length,samples[75]);
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
