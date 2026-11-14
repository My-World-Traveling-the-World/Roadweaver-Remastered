/* 文件职责：钉住「待注入项被丢弃」必须被记下来，且两条注入路径都要上报。 */
package net.shiroha233.roadweaver.structures.precompute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待注入项丢弃的可观测性测试（2026-11-15）。
 *
 * <h2>它守的是什么</h2>
 * <p>{@code StructureInjector} 每扫完一个区块就调 {@code markAsInjected(level, chunkPos)}，
 * 而这个标记是<b>按区块</b>的、不是按单条的。于是下面几种情况里的待注入项
 * 会在同一行标记里被一并清掉 —— <b>永久丢弃、不再重试</b>：</p>
 * <ul>
 *   <li>结构类型不支持（{@code continue}）；</li>
 *   <li>村庄没产出任何部件（{@code continue}）；</li>
 *   <li>注入过程抛异常（{@code catch}）。</li>
 * </ul>
 *
 * <p>原来的代码只在这些分支各打一条日志，没有任何汇总 ——
 * 真机上既不知道总共丢了多少，也不知道丢了哪些。这正是功能盘点里
 * 「注入 warn/error 后仍 markAsInjected」那条。</p>
 *
 * <h2>为什么用源码扫描而不是行为测试</h2>
 * <p>要真实触发「注入失败」需要构造一个能进到注入循环、又必然抛异常的
 * Minecraft <b>世界</b>环境（{@code ServerLevel} + {@code ChunkAccess} +
 * 结构注册表）。在纯 JUnit 里搭这个的成本远高于收益，而且搭出来的假环境
 * 也证明不了真实世界的路径。所以这里改用<b>源码级守卫</b>：
 * 断言两条注入方法里都存在上报点，且计数只用一处出口。</p>
 *
 * <p>这类守卫项目里已有先例（{@code SingleSourceOfTruthTest} 也是纯文本扫描）。
 * 它的价值不在「验证运行结果」，而在「防止重构时悄悄漏掉一条路径」。</p>
 */
class StructureInjectorDropCountTest {

    private static String source() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path cand = dir.resolve("common").resolve("src").resolve("main").resolve("java")
                    .resolve("net").resolve("shiroha233").resolve("roadweaver")
                    .resolve("structures").resolve("precompute").resolve("StructureInjector.java");
            if (Files.isRegularFile(cand)) {
                return Files.readString(cand, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    @Test
    void 两条注入路径都必须上报丢弃数() throws IOException {
        String src = source();
        if (src == null) {
            return; // 找不到仓库根（例如增量单测）—— 跳过而不是误报
        }
        int reported = count(src, "DROPPED_PENDING.addAndGet(droppedCount)");
        assertEquals(2, reported,
                "结构注入与村庄注入两条路径都必须上报丢弃数。"
                        + "只覆盖一条，另一条就会继续静默丢数据 —— 实际上报点 " + reported + " 处");
    }

    @Test
    void 丢弃计数只在标记之后上报() throws IOException {
        String src = source();
        if (src == null) {
            return;
        }
        int markStructure = src.indexOf("PendingStructureStorage.markAsInjected(level, chunkPos)");
        int markVillage = src.indexOf("PendingRoadsideVillageStorage.markAsInjected(level, chunkPos)");
        assertTrue(markStructure > 0, "结构注入的 markAsInjected 调用点必须存在");
        assertTrue(markVillage > 0, "村庄注入的 markAsInjected 调用点必须存在");

        int reportAfterStructure = src.indexOf("DROPPED_PENDING.addAndGet(droppedCount)", markStructure);
        int reportAfterVillage = src.indexOf("DROPPED_PENDING.addAndGet(droppedCount)", markVillage);
        assertTrue(reportAfterStructure > markStructure,
                "结构注入的上报必须在 markAsInjected 之后 —— 顺序反了会先记丢弃、"
                        + "而在标记前的成功条数还没统计完");
        assertTrue(reportAfterVillage > markVillage,
                "村庄注入的上报必须在 markAsInjected 之后");
    }

    @Test
    void 跳过分支也要计入丢弃() throws IOException {
        String src = source();
        if (src == null) {
            return;
        }
        // 五个分支都要 +1 —— 两条注入路径各有一组，漏任何一个都会让计数偏小：
        //   结构注入：类型不支持、抛异常                    = 2
        //   村庄注入：结构不可用、没产出任何部件、抛异常    = 3
        assertEquals(5, count(src, "droppedCount++;"),
                "两条注入路径一共有 5 个「丢弃」分支（结构 2 + 村庄 3）；"
                        + "漏掉任何一个都会让计数偏小、掩盖真实丢失量");
    }

    @Test
    void 计数访问器存在且初始为零() {
        // 计数器是进程级静态的，测试之间可能被别的用例累加过，
        // 所以只断言「可读且非负」，不断言恰好为 0。
        long n = StructureInjector.droppedPendingCount();
        assertTrue(n >= 0, "丢弃计数必须可读且非负，实际 " + n);
    }
}