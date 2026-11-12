package net.shiroha233.roadweaver.persistence.files;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * **checkpoint 内存缓存的回归**（★ 0.8.401）。
 *
 * <h2>要防什么</h2>
 * 真机 3× jstack 取证（2026-09-27，Chunky 预热期，游戏已跑 10 小时仍未完成）：
 * 三次采样服务端线程**全部**卡在同一处 —— 磁盘 IO：
 *
 * <pre>
 *   at Files.newBufferedReader / WindowsNativeDispatcher.CreateFile0
 *     at InitialGenerationCheckpointStore.hasCompleted(:89) / isActive(:60)
 *     at InitialGenManager.shouldRunInitialGeneration(:210-211)
 *     at RoadPlanningReadinessIndex.adoptCompletedInitialCoverage(:374)
 *     at RoadPlanningReadinessIndex.requestRegion(:76)
 *     at ChunkyRoadAwareCoordinator.startWholeSelectionWhenReadyLocked(:219)
 * </pre>
 *
 * <p>调用规模：Chunky 每 tick 遍历 3190 个 region，每个都经过
 * {@code shouldRunInitialGeneration}，而后者<b>连续读两次盘</b>；
 * 又因存在两个独立 mixin 注入同一 tick 钩子，整批扫描每 tick 跑两遍：</p>
 *
 * <pre>
 *   2754 region × 2 遍 × 2 次读盘 ≈ 1.1 万次文件打开/tick ≈ 22 万次/秒
 * </pre>
 *
 * <p>这解释了全部现象（tick ≈ 0.8s、{@code critical=true}、lanes 被压到 1、
 * 「Can't keep up 230s」）—— 而 lane 限速对此完全无效，因为瓶颈是 IO 不是 CPU。</p>
 *
 * <h2>修法与它依赖的不变量</h2>
 * 加内存缓存（{@link InitialGenerationCheckpointStore}）。缓存之所以安全，
 * 依赖三条性质，本测试**逐条钉住**：
 * <ol>
 *   <li><b>重复读不触发磁盘 IO</b> —— 这是修复本体（省掉那 1.1 万次/tick）。</li>
 *   <li><b>写入后缓存失效</b> —— 否则会读到过期值，比慢更糟（正确性错误）。
 *       所有写入口（begin / recordProgress / markCompleted / clear）都必须失效。</li>
 *   <li><b>不同 key 互不干扰</b> —— 按 level 隔离，防跨存档/跨维度串味。</li>
 * </ol>
 *
 * <p>同时钉住<b>读失败时的降级语义不变</b>：文件不存在或读不出时，
 * 仍返回「未激活 / 未完成 / 无断点」，与原实现一致。</p>
 */
public final class CheckpointCacheTest {

    private CheckpointCacheTest() {
    }

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("roadweaver-checkpoint-cache-");
        try {
            testRepeatedReadsDoNotTouchDisk(dir);
            testWriteInvalidatesCache(dir);
            testDifferentKeysAreIsolated(dir);
            testMissingFileDegradesLikeBefore(dir);
            testCompletedAndResumePointParsed(dir);
            testCacheStatsExposed(dir);

            System.out.println();
            System.out.println("passed=" + passed + " failed=" + failed);
            if (failed > 0) {
                throw new AssertionError(failed + " check(s) failed");
            }
            System.out.println("CheckpointCacheTest: PASS");
        } finally {
            deleteTreeQuietly(dir);
        }
    }

    /**
     * **核心**：重复读同一 key 只应读盘一次。
     *
     * <p>真机预热期每 tick 约 1.1 万次读盘；这条断言把它钉成 1 次。</p>
     */
    private static void testRepeatedReadsDoNotTouchDisk(Path dir) throws Exception {
        InitialGenerationCheckpointStore.resetCacheForTest();
        Path file = dir.resolve("checkpoint-active.txt");
        Files.writeString(file, "schema=2\nstate=ACTIVE\nphase=PLANNING\ndone=1\ntotal=2\n",
                StandardCharsets.UTF_8);

        Object key = new Object();

        InitialGenerationCheckpointStore.State first =
                InitialGenerationCheckpointStore.cachedState(key, file);
        check("首次读取应报告 active", first.active);
        check("首次读取后读盘次数=1",
                InitialGenerationCheckpointStore.diskReadCountForTest() == 1L);

        // 连续 5000 次（模拟一个 tick 内的高频调用）
        for (int i = 0; i < 5000; i++) {
            InitialGenerationCheckpointStore.State s =
                    InitialGenerationCheckpointStore.cachedState(key, file);
            if (!s.active) {
                check("第 " + i + " 次重复读仍应为 active", false);
                break;
            }
        }
        check("重复 5000 次后读盘次数仍为 1（未再触盘）",
                InitialGenerationCheckpointStore.diskReadCountForTest() == 1L);
        check("命中计数达到 5000",
                InitialGenerationCheckpointStore.cacheHitCountForTest() >= 5000L);
    }

    /**
     * **写入必须使缓存失效** —— 否则读到过期值，是正确性错误。
     */
    private static void testWriteInvalidatesCache(Path dir) throws Exception {
        InitialGenerationCheckpointStore.resetCacheForTest();
        Path file = dir.resolve("checkpoint-invalidate.txt");
        Files.writeString(file, "schema=2\nstate=ACTIVE\n", StandardCharsets.UTF_8);

        Object key = new Object();
        check("初始为 active",
                InitialGenerationCheckpointStore.cachedState(key, file).active);
        check("ACTIVE 时 completed=false",
                !InitialGenerationCheckpointStore.cachedState(key, file).completed);

        // 外部改写（模拟写入口写了 COMPLETED，然后失效缓存）
        Files.writeString(file, "schema=2\nstate=COMPLETED\n", StandardCharsets.UTF_8);
        check("未失效前仍读旧值（缓存生效的证据）",
                InitialGenerationCheckpointStore.cachedState(key, file).active);

        InitialGenerationCheckpointStore.invalidateForTest(key);
        long before = InitialGenerationCheckpointStore.diskReadCountForTest();
        InitialGenerationCheckpointStore.State after =
                InitialGenerationCheckpointStore.cachedState(key, file);
        check("失效后应读到新值 completed=true", after.completed);
        check("失效后确实重新读盘",
                InitialGenerationCheckpointStore.diskReadCountForTest() == before + 1);
    }

    /**
     * **按 key 隔离** —— 模拟不同存档/维度各有各的检查点。
     */
    private static void testDifferentKeysAreIsolated(Path dir) throws Exception {
        InitialGenerationCheckpointStore.resetCacheForTest();
        Path fileA = dir.resolve("cp-a.txt");
        Path fileB = dir.resolve("cp-b.txt");
        Files.writeString(fileA, "schema=2\nstate=ACTIVE\n", StandardCharsets.UTF_8);
        Files.writeString(fileB, "schema=2\nstate=COMPLETED\n", StandardCharsets.UTF_8);

        Object keyA = new Object();
        Object keyB = new Object();

        InitialGenerationCheckpointStore.State a =
                InitialGenerationCheckpointStore.cachedState(keyA, fileA);
        InitialGenerationCheckpointStore.State b =
                InitialGenerationCheckpointStore.cachedState(keyB, fileB);

        check("keyA 是 active", a.active && !a.completed);
        check("keyB 是 completed", b.completed && !b.active);
        check("两个 key 各读盘一次", InitialGenerationCheckpointStore.diskReadCountForTest() == 2L);

        // 失效 A 不应影响 B
        InitialGenerationCheckpointStore.invalidateForTest(keyA);
        InitialGenerationCheckpointStore.State bAgain =
                InitialGenerationCheckpointStore.cachedState(keyB, fileB);
        check("失效 keyA 后 keyB 仍命中缓存（未新增读盘）",
                bAgain.completed
                        && InitialGenerationCheckpointStore.diskReadCountForTest() == 2L);
    }

    /**
     * **文件不存在时的降级语义必须与原实现一致**：
     * active=false / completed=false / 无断点，且**不应反复尝试读盘**。
     */
    private static void testMissingFileDegradesLikeBefore(Path dir) throws Exception {
        InitialGenerationCheckpointStore.resetCacheForTest();
        Path missing = dir.resolve("does-not-exist.txt");
        Object key = new Object();

        InitialGenerationCheckpointStore.State s =
                InitialGenerationCheckpointStore.cachedState(key, missing);
        check("缺失文件 -> active=false", !s.active);
        check("缺失文件 -> completed=false", !s.completed);
        check("缺失文件 -> 无断点", s.resumePoint.isEmpty());

        for (int i = 0; i < 100; i++) {
            InitialGenerationCheckpointStore.cachedState(key, missing);
        }
        check("缺失文件的负结果也被缓存（100 次重复只读盘 1 次）",
                InitialGenerationCheckpointStore.diskReadCountForTest() == 1L);
    }

    /**
     * **COMPLETED 与断点解析**：确认缓存不改变解析结果。
     */
    private static void testCompletedAndResumePointParsed(Path dir) throws Exception {
        InitialGenerationCheckpointStore.resetCacheForTest();
        Path file = dir.resolve("cp-resume.txt");
        Files.writeString(file, "schema=2\nstate=ACTIVE\nphase=ROAD_BUILD\ndone=7\ntotal=42\n",
                StandardCharsets.UTF_8);

        InitialGenerationCheckpointStore.State s =
                InitialGenerationCheckpointStore.cachedState(new Object(), file);
        check("active=true", s.active);
        check("completed=false", !s.completed);
        check("断点存在", s.resumePoint.isPresent());
        check("断点 phase 正确",
                s.resumePoint.map(InitialGenerationCheckpointStore.ResumePoint::phaseName)
                        .orElse("").equals("ROAD_BUILD"));
        check("断点 done 正确",
                s.resumePoint.map(InitialGenerationCheckpointStore.ResumePoint::done).orElse(-1) == 7);
        check("断点 total 正确",
                s.resumePoint.map(InitialGenerationCheckpointStore.ResumePoint::total).orElse(-1) == 42);
    }

    /** 诊断 API 可用（真机据此确认读盘已归零）。 */
    private static void testCacheStatsExposed(Path dir) throws Exception {
        InitialGenerationCheckpointStore.resetCacheForTest();
        Path file = dir.resolve("cp-stats.txt");
        Files.writeString(file, "schema=2\nstate=ACTIVE\n", StandardCharsets.UTF_8);
        Object key = new Object();
        InitialGenerationCheckpointStore.cachedState(key, file);
        for (int i = 0; i < 10; i++) {
            InitialGenerationCheckpointStore.cachedState(key, file);
        }
        String stats = InitialGenerationCheckpointStore.cacheStats();
        check("cacheStats 含 misses 字段", stats.contains("misses"));
        check("cacheStats 报告 1 次读盘", stats.contains("misses(实际读盘)=1"));
    }

    // ─────────────────── 辅助 ───────────────────

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }
}
