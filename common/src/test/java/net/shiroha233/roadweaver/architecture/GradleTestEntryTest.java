/* 文件职责：守卫 gradle 里注册的测试入口都指向真实存在的类。 */
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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：{@code common/build.gradle} 里注册的测试入口必须指向真实存在的类。
 *
 * <h2>为什么需要这条（2026-11-13 实测）</h2>
 * <p>{@code common/build.gradle} 用 {@code registerMainRegression('任务名', '全限定类名')}
 * 注册了一批 {@code JavaExec} 任务，它们全部挂在 {@code tasks.named('test')} 的
 * {@code dependsOn} 上。第二个参数写错时：</p>
 * <ul>
 *   <li><b>配置期不报错</b> —— Gradle 只存字符串，不解析类；</li>
 *   <li><b>只有真跑那个任务时才 {@code ClassNotFoundException}</b>；</li>
 *   <li>而没人天天跑 {@code :common:test}（{@code verify.ps1} 只跑
 *       {@code :common:roadJUnitRegressionSuite}）⇒ <b>错误可以躺很久</b>。</li>
 * </ul>
 *
 * <p>实测后果：重制把包从 {@code features.path.pathlogic.core} 改到
 * {@code pathlogic.*}、从 {@code pathfinding.cache.opencl} 改到
 * {@code pathfinding.sampling.opencl}，但 <b>7 个任务还写着旧包名</b>。
 * 这 7 个 harness 共 <b>101 处断言从未执行过</b>。修好类名后第一次跑，
 * {@code coarseLaneCountBoundary} 立刻红了一条 —— 而且因为
 * {@code if (src == null) return;} 提前返回，它<b>真正有价值的 2 条断言
 * （"源码不得再出现截断写法"）从来没跑过</b>。</p>
 *
 * <h2>双重掩护为什么危险</h2>
 * <p>同一个错误在两层同时存在时，它们会互相掩护：</p>
 * <ul>
 *   <li>gradle 任务类名错 ⇒ harness 不执行 ⇒ 里面的断言不会红；</li>
 *   <li>harness 不红 ⇒ 没人怀疑 gradle 任务名有问题。</li>
 * </ul>
 * <p>所以这道守卫查的是<b>入口</b>，而 {@link RegressionRegistrationTest} 查的是
 * <b>JUnit 套件清单</b>。两条合起来才盖住「测试写了但没跑」的全部形态。</p>
 */
public final class GradleTestEntryTest {

    /** 相对 common 模块根。 */
    private static final Path BUILD_FILE = Paths.get("build.gradle");

    private static final Path TEST_SRC = Paths.get("src", "test", "java");

    private static final Path MAIN_SRC = Paths.get("src", "main", "java");

    /** registerMainRegression('任务名', '全限定类名') —— 允许换行与缩进。 */
    private static final Pattern REGISTERED = Pattern.compile(
            "registerMainRegression\\(\\s*'([^']+)'\\s*,\\s*'([\\w.]+)'\\s*\\)", Pattern.DOTALL);

    /** 任意单引号包裹的全限定类名（用于扫其它 register 形式）。 */
    private static final Pattern ANY_FQCN = Pattern.compile("'(net\\.shiroha233\\.roadweaver\\.[\\w.]+)'");

    @BeforeAll
    static void locate() {
        // ★ 2026-11-15（独立复核）：同 RegressionRegistrationTest，
        // 「找不到就跳过」会让守卫静默失效 —— 改成硬失败。
        assertTrue(Files.isRegularFile(BUILD_FILE),
                "找不到 " + BUILD_FILE.toAbsolutePath()
                        + " —— 本守卫需要读取 gradle 入口配置，找不到就无法校验。");
    }

    private static Path classFile(String fqcn) {
        String rel = fqcn.replace('.', java.io.File.separatorChar) + ".java";
        Path inTest = TEST_SRC.resolve(rel);
        if (Files.isRegularFile(inTest)) return inTest;
        Path inMain = MAIN_SRC.resolve(rel);
        if (Files.isRegularFile(inMain)) return inMain;
        return null;
    }

    @Test
    void 注册的测试类名必须都能解析到() throws IOException {
        String text = Files.readString(BUILD_FILE, StandardCharsets.UTF_8);

        List<String> dangling = new ArrayList<>();
        Matcher m = REGISTERED.matcher(text);
        int total = 0;
        while (m.find()) {
            total++;
            String task = m.group(1);
            String fqcn = m.group(2);
            if (classFile(fqcn) == null) {
                dangling.add(task + "  ->  " + fqcn);
            }
        }
        assertTrue(total > 0, "在 common/build.gradle 里没找到任何 registerMainRegression 注册");
        assertTrue(dangling.isEmpty(),
                "这些 gradle 测试任务的类名在源码里找不到 —— 任务会存在但跑不起来"
                        + "（ClassNotFoundException），于是这些 harness 里的断言永远不会执行：\n  "
                        + String.join("\n  ", dangling)
                        + "\n修法：把类名改成重制后的真实包名。多模块仓库的包会搬，"
                        + "写完请自己跑一次该任务确认它能通过。");
    }

    @Test
    void 构建脚本里引用的道路类都必须存在() throws IOException {
        String text = Files.readString(BUILD_FILE, StandardCharsets.UTF_8);
        List<String> dangling = new ArrayList<>();
        Matcher m = ANY_FQCN.matcher(text);
        while (m.find()) {
            String fqcn = m.group(1);
            if (classFile(fqcn) == null && !dangling.contains(fqcn)) {
                dangling.add(fqcn);
            }
        }
        assertTrue(dangling.isEmpty(),
                "common/build.gradle 里引用了不存在的道路类：\n  "
                        + String.join("\n  ", dangling));
    }
}