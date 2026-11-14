/* 文件职责：钉住「结构验证整批全放行」必须常驻可观测，而不是只在调试开关下可见。 */
package net.shiroha233.roadweaver.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构验证「全部放行」的可观测性测试（2026-11-15）。
 *
 * <h2>它守的是什么</h2>
 * <p>{@code StructureVerificationService.verifyPredictedStructures} 有六条
 * 「无法验证 ⇒ 保留原样」的返回路径（拿不到 {@code ServerChunkCache}、
 * {@code chunkScanner()} 抛错、结构 id 解析失败、注册表里没这个结构、
 * 拿不到 holder、没有 placement）。</p>
 *
 * <p>问题在于：<b>「无法验证」与「验证了且全部通过」的返回值完全一样</b>
 * —— 都是原样返回全部预测项。而生产环境里唯一能看出比例的那行日志
 * 卡在 {@code roadweaver.structureCacheDebug} 开关后面（默认关闭），
 * 等于<b>默认情况下没人知道验证到底有没有在工作</b>。</p>
 *
 * <h2>为什么只做计数不做判断</h2>
 * <p>「整批全放行」本身<b>不是错误</b> —— 预测本来就准的时候它就该全放行。
 * 所以这里只累加计数、并在首次给一条提示性 warn，<b>不改任何判定</b>。
 * 把这个数字当判决去拦截，会误伤正常运行的世界。</p>
 */
class StructureVerificationObservabilityTest {

    private static String source() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path cand = dir.resolve("common").resolve("src").resolve("main").resolve("java")
                    .resolve("net").resolve("shiroha233").resolve("roadweaver")
                    .resolve("search").resolve("StructureIndexService.java");
            if (Files.isRegularFile(cand)) {
                return Files.readString(cand, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        return null;
    }

    @Test
    void 全放行统计必须常驻不受调试开关控制() throws IOException {
        String src = source();
        if (src == null) {
            return; // 找不到仓库根则跳过，不误报
        }
        int inc = src.indexOf("FULL_PASS_BATCHES.incrementAndGet()");
        assertTrue(inc > 0,
                "「整批全放行」的统计必须存在 —— 否则验证失效在默认配置下完全不可见");

        int returnOut = src.indexOf("return out;", inc);
        assertTrue(returnOut > inc,
                "统计必须在 return out 之前执行，否则永远走不到");

        // 关键判据：必须有一条**独立于调试开关**的提示日志。
        // 缩进/位置都不可靠（if (CACHE_DEBUG) 块与普通块的缩进相同），
        // 但「一条专属文案的 warn」能直接证明它不在调试分支里 ——
        // 因为调试块里那条日志的文案是 "StructureIndexService.predictAndVerifyInRect"。
        int warnAt = src.indexOf("let every predicted structure through");
        assertTrue(warnAt > 0,
                "必须有一条不依赖 CACHE_DEBUG 的提醒日志 —— "
                        + "否则「验证失效」在默认配置下仍然完全不可见");
        assertTrue(src.contains("LOGGER.warn(\"RoadWeaver structure verification let every predicted structure through"),
                "这条提醒必须是 warn 级且带独立文案，便于真机 grep");
    }

    @Test
    void 计数访问器存在且可重置() {
        StructureIndexService.resetFullPassBatchCount();
        assertEquals(0, StructureIndexService.fullPassBatchCount(), "重置后应为 0");

        long n = StructureIndexService.fullPassBatchCount();
        assertTrue(n >= 0, "计数必须可读且非负，实际 " + n);
    }

    @Test
    void 判定逻辑不得被这个计数器改变() throws IOException {
        String src = source();
        if (src == null) {
            return;
        }
        // 计数器只允许以 incrementAndGet / get / set 的形式出现，
        // 不允许被读进任何条件分支去改变返回结果。
        assertTrue(!src.contains("if (FULL_PASS_BATCHES.get()"),
                "这个计数器是纯取证，不得参与任何决策 —— "
                        + "「整批全放行」本身不是错误，拿它去拦截会误伤正常世界");
        assertTrue(!src.contains("fullPassBatchCount() >"),
                "同上：访问器只能用于读数，不能进条件判断");
    }
}