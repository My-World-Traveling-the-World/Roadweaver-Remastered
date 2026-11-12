package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.pathfinding.sampling.CoarseHeightBatchRequest;
import net.shiroha233.roadweaver.pathfinding.sampling.CoarseHeightBatchSampler;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.runtime.RuntimePlayerActivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 粗采样 GPU 提交间隔闸的「按逻辑瓦片计费」回归。
 *
 * <p>背景：{@code CoarseTerrainTileSampler.sampleHeightsBanded} 把一个逻辑瓦片切成
 * {@code coarseHeightBandCount} 条带，每条带都走一次 {@code RuntimeCoarseGpuBudget.acquire}。
 * 若间隔闸仍在 acquire 里刷新 {@code LAST_GPU_SUBMIT}，单瓦片会被放大成 N×interval 的空等
 * （live bands=6 × active 14s ≈ 70s）。修复后间隔闸由
 * {@code RuntimeCoarseGpuBudget.awaitTileSubmitInterval()} 在瓦片层只计费一次。</p>
 *
 * <p>本测试用反射调用私有 {@code sampleHeightsBanded}（不依赖 Minecraft 世界 / OpenCL），
 * 用「6 带 × 150ms 间隔若逐带计费需约 750ms、逐瓦片计费应 &lt;400ms」这一时间上界
 * 钉住「一个逻辑瓦片的多带只触发一次间隔闸」。</p>
 */
final class CoarseTerrainTileSubmitIntervalBillingTest {

    private static final int BANDS = 6;
    private static final int INTERVAL_MS = 150;
    private static final int BILLED_ONCE_UPPER_BOUND_MS = 400;

    private Method sampleHeightsBanded;
    private Field lastGpuSubmit;

    @BeforeEach
    void setUp() throws Exception {
        // 把相位钉在 GAMEPLAY_IDLE：不开局、不 Chunky、无玩家活动。
        InitialGenManager.resetForServerStop();
        RuntimeLoadPolicy.setChunkyPreflightActive(false);
        RuntimePlayerActivity.reset();

        ConfigService.get().performance().setCoarseHeightBandCount(BANDS);
        ConfigService.get().performance().setRuntimeCoarseGpuMinIntervalMs(INTERVAL_MS);
        ConfigService.get().performance().setRuntimeCoarseGpuActiveMinIntervalMs(INTERVAL_MS);
        // 关闭 worker 占空比节流，避免 beforeBackgroundUnit 里混入每带约 20ms 的 sleep。
        ConfigService.get().performance().setThreadDutyCycle(100);

        sampleHeightsBanded = CoarseTerrainTileSampler.class.getDeclaredMethod(
                "sampleHeightsBanded",
                CoarseHeightBatchSampler.class, int.class, int.class, int.class, int.class, int.class);
        sampleHeightsBanded.setAccessible(true);

        lastGpuSubmit = Class.forName(
                "net.shiroha233.roadweaver.pathfinding.sampling.opencl.RuntimeCoarseGpuBudget")
                .getDeclaredField("LAST_GPU_SUBMIT");
        lastGpuSubmit.setAccessible(true);
        lastGpuSubmitAtomic().set(0L);
    }

    @AfterEach
    void tearDown() throws Exception {
        ConfigService.get().performance().setCoarseHeightBandCount(4);
        ConfigService.get().performance().setRuntimeCoarseGpuMinIntervalMs(1500);
        ConfigService.get().performance().setRuntimeCoarseGpuActiveMinIntervalMs(1500);
        ConfigService.get().performance().setThreadDutyCycle(50);
        if (lastGpuSubmit != null) {
            lastGpuSubmitAtomic().set(0L);
        }
    }

    @Test
    void multiBandTileChargesSubmitIntervalOnce() throws Exception {
        FakeGpuSampler sampler = new FakeGpuSampler();

        long startedAt = System.nanoTime();
        int[] heights = (int[]) sampleHeightsBanded.invoke(null, sampler, 0, 0, 4, 16, 48);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertNotNull(heights, "banded sampling must return a result");
        assertEquals(16 * 48, heights.length, "result must cover the full expanded tile");
        assertEquals(BANDS, sampler.calls.get(), "a 6-band tile must submit exactly 6 bands");
        assertTrue(elapsedMs < BILLED_ONCE_UPPER_BOUND_MS,
                "one logical tile must bill the submit interval once, not per band; elapsedMs=" + elapsedMs);
        assertTrue(lastGpuSubmitAtomic().get() != 0L,
                "the tile must record its submit timestamp exactly once");
    }

    private AtomicLong lastGpuSubmitAtomic() throws Exception {
        return (AtomicLong) lastGpuSubmit.get(null);
    }

    private static final class FakeGpuSampler implements CoarseHeightBatchSampler {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public int[] sampleHeights(CoarseHeightBatchRequest request) {
            calls.incrementAndGet();
            return new int[Math.multiplyExact(request.sampleWidth(), request.sampleHeight())];
        }

        @Override
        public boolean isAccelerated() {
            return true;
        }
    }
}
