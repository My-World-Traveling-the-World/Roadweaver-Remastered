/* 文件职责：把 route 包里 11 个「只有 main 的自测」接进 JUnit 回归 —— 它们此前从未自动执行。 */
package net.shiroha233.roadweaver.route;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * route 自测接入器（2026-11-15）。
 *
 * <h2>它解决的问题：一整片测试从未被执行</h2>
 * <p>{@code common/src/test/java/.../route/} 下有 <b>11 个类</b>，它们看起来是测试
 * （文件名以 {@code Test} 结尾、里面逐条 {@code require(...)}），但它们是
 * <b>手写自测类</b>：只有 {@code public static void main(String[])}，<b>一个 {@code @Test} 都没有</b>。</p>
 *
 * <p>后果：{@code RoadJUnitRegressionSuite} 是唯一被 Gradle 执行的入口，
 * 它按类名登记 JUnit 测试 —— 这 11 个类不在其中，也永远不会被 JUnit 发现。
 * 也就是说，<b>route 包（约 10,000 行，含手绘路线编辑器、存档 Codec、预加载、平滑器）
 * 的自动化覆盖实际为零</b>，那些 {@code require(...)} 只有在有人手工
 * {@code java -cp ... RouteCodecTest} 时才会跑 —— 而没有人会这么做。</p>
 *
 * <h2>为什么用反射调 main 而不是把它们改写成 JUnit</h2>
 * <p>改写成 JUnit 需要逐个重排每个自测的结构（它们的断言是 {@code require} 而非
 * {@code assertEquals}，且每个 {@code main} 自带 {@code SharedConstants.tryDetectVersion()} +
 * {@code Bootstrap.bootStrap()}），改动面大且容易在搬运中丢断言。
 * 反射调用 {@code main} 是<b>零侵入</b>的：原文件一行不改，照样能被回归覆盖。</p>
 *
 * <p>前提条件（已核实）：这 11 个类<b>都没有 {@code System.exit}</b>，
 * 所以调用 {@code main} 不会杀掉 Gradle 的测试 JVM；失败时它们抛异常，
 * 这里转成 JUnit 失败并带上类名。</p>
 *
 * <h2>将来该怎么改</h2>
 * <p>如果哪天要正式改写，正确做法是<b>一次一个类地搬</b>：搬完把该类从这个名单里删掉，
 * 让 {@link #SELF_TESTS} 单调缩小。这样任何时刻都不会出现「搬了一半、两边都没跑」的窗口。</p>
 */
class RouteSelfTestRunnerTest {

    /**
     * 需要接入的自测类（全限定名）。
     *
     * <p>这份名单是<b>穷举</b>的 —— route 包下所有「只有 main、没有 @Test」的类都在这里。
     * 新增此类自测时请一并登记，否则它又会变回「永远不跑」。</p>
     */
    private static final String[] SELF_TESTS = {
            "net.shiroha233.roadweaver.route.RouteCodecTest",
            "net.shiroha233.roadweaver.route.RouteSmootherTest",
            "net.shiroha233.roadweaver.route.RouteSegmentMapperTest",
            "net.shiroha233.roadweaver.route.RouteRoadDataPatcherTest",
            "net.shiroha233.roadweaver.route.RouteBuildProgressTest",
            "net.shiroha233.roadweaver.route.RoutePreloadTimingTest",
            "net.shiroha233.roadweaver.route.RoutePreloadFallbackGuardTest",
            "net.shiroha233.roadweaver.route.RoutePreloadCallSiteGuardTest",
            "net.shiroha233.roadweaver.route.PlayerRoutePendingCountTest",
            "net.shiroha233.roadweaver.route.RouteAuditRound3Test",
            "net.shiroha233.roadweaver.route.AuditFixRegressionTest",
    };

    @Test
    void 名单里的每个自测类都必须存在且有main() {
        for (String fqcn : SELF_TESTS) {
            try {
                Class<?> type = Class.forName(fqcn);
                Method main = type.getMethod("main", String[].class);
                assertTrue(java.lang.reflect.Modifier.isStatic(main.getModifiers()),
                        fqcn + " 的 main 必须是 static");
            } catch (ClassNotFoundException e) {
                fail("自测类不存在：" + fqcn + "（改名后请同步本名单）");
            } catch (NoSuchMethodException e) {
                fail(fqcn + " 没有 main(String[]) —— 若它已改写成 JUnit，请从 SELF_TESTS 删掉");
            }
        }
    }

    /**
     * 逐个执行自测；任何一个失败都会让整个测试红并指出是哪个类。
     *
     * <p>一次跑完全部而不是「失败即停」—— 这样一轮就能看到<b>所有</b>坏掉的自测，
     * 不至于修一个跑一次。</p>
     */
    @Test
    void 全部自测都必须通过() {
        StringBuilder failures = new StringBuilder();
        int passed = 0;
        for (String fqcn : SELF_TESTS) {
            try {
                Class<?> type = Class.forName(fqcn);
                Method main = type.getMethod("main", String[].class);
                main.invoke(null, (Object) new String[0]);
                passed++;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                failures.append("\n  ").append(fqcn).append(" → ").append(cause);
            } catch (Throwable t) {
                failures.append("\n  ").append(fqcn).append(" → 调用失败 ").append(t);
            }
        }
        if (failures.length() > 0) {
            fail("route 自测有失败（" + passed + "/" + SELF_TESTS.length + " 通过）：" + failures);
        }
        assertTrue(passed > 0, "至少要跑通一个自测，实际 " + passed);
    }
}