/* 文件职责：回归套件完备性守卫 —— 每个带 @Test 的测试类都必须真的被跑。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归套件完备性守卫：<b>带 {@code @Test} 的测试类不许漏跑</b>。
 *
 * <h2>为什么需要这条守卫（2026-11-13）</h2>
 * <p>这个仓库的测试入口不是 {@code :common:test}（那个任务把 JUnit 发现清空了，
 * 永远显示 {@code NO-SOURCE}），而是 {@code RoadJUnitRegressionSuite} 里
 * <b>一份手写的 {@code DiscoverySelectors.selectClass(...)} 清单</b>。</p>
 *
 * <p>手写清单的问题是：<b>新写一个测试类、忘了登记，它就会永远不跑，而且没有任何提示</b> ——
 * CI 依然是绿的，覆盖率却悄悄少了一块。</p>
 *
 * <p>独立审计实测：{@code SharedEndpointPathReuseTest} 有 4 个 {@code @Test}，
 * 从未被登记，因此 <b>4 个用例从未执行过</b>。补登记后回归从 689 涨到 693，
 * 而且它们<b>本来就是通过的</b> —— 也就是说这纯粹是「白写了」。</p>
 *
 * <h2>它检查什么</h2>
 * <ol>
 *   <li>{@code src/test/java} 下每个含 {@code @Test} 的 {@code *Test.java}
 *       必须出现在回归套件的 {@code selectClass} 清单里；</li>
 *   <li>反过来：清单里登记的类必须真实存在（防止改名后留下死条目）。</li>
 * </ol>
 *
 * <h2>它不检查什么</h2>
 * <p>不含 {@code @Test} 的 {@code *Test.java}（辅助类、纯 main 的 harness）不在范围内 ——
 * 它们本来就跑不了，登记进 JUnit 套件没有意义。</p>
 */
public final class RegressionRegistrationTest {

    /** 回归套件（聚合器）的源码路径，相对 common 模块根。 */
    private static final Path SUITE = Paths.get("src", "test", "java",
            "net", "shiroha233", "roadweaver", "RoadJUnitRegressionSuite.java");

    private static final Path TEST_ROOT = Paths.get("src", "test", "java");

    /** 匹配 selectClass("全限定名") —— 允许换行与缩进。 */
    private static final Pattern SELECT_CLASS = Pattern.compile(
            "selectClass\\(\\s*\"([^\"]+)\"", Pattern.DOTALL);

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);

    @BeforeAll
    static void locate() {
        Assumptions.assumeTrue(Files.isRegularFile(SUITE),
                "工作目录不是 common 模块（找不到 " + SUITE + "），跳过");
    }

    private static java.util.Set<String> registered() throws IOException {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        Matcher m = SELECT_CLASS.matcher(Files.readString(SUITE, StandardCharsets.UTF_8));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void 带Test注解的测试类必须全部登记进回归套件() throws IOException {
        java.util.Set<String> registered = registered();
        assertTrue(registered.size() >= 100,
                "回归套件只登记了 " + registered.size() + " 个类，清单可能被清空了");

        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith("Test.java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (!text.contains("@Test")) continue;          // 辅助类/纯 harness，跳过
                Matcher pm = PACKAGE.matcher(text);
                if (!pm.find()) continue;
                String base = f.getFileName().toString().replace(".java", "");
                String fq = pm.group(1) + "." + base;
                if (!registered.contains(fq)) missing.add(fq);
            }
        }
        assertTrue(missing.isEmpty(),
                "这些测试类含 @Test 却没有登记进 RoadJUnitRegressionSuite，"
                        + "因此永远不会被执行（回归全绿但覆盖率是假的）：\n  "
                        + String.join("\n  ", missing)
                        + "\n修法：把全限定名加进 RoadJUnitRegressionSuite 的 selectClass 清单。");
    }

    @Test
    void 套件里登记的类必须真实存在() throws IOException {
        List<String> dangling = new ArrayList<>();
        for (String fq : registered()) {
            Path p = TEST_ROOT.resolve(fq.replace('.', java.io.File.separatorChar) + ".java");
            if (!Files.isRegularFile(p)) dangling.add(fq);
        }
        assertTrue(dangling.isEmpty(),
                "回归套件里登记了不存在的测试类（改名/删除后留下的死条目）：\n  "
                        + String.join("\n  ", dangling));
    }
}