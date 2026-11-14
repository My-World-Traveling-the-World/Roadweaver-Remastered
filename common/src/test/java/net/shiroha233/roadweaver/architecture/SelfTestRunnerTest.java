/* 文件职责：把全仓 32 个「只有 main 的手写自测」接进 JUnit 回归 —— 它们此前从未被自动执行。 */
package net.shiroha233.roadweaver.architecture;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 手写自测接入器（2026-11-15）。
 *
 * <h2>它解决的问题：一整批测试从未被执行</h2>
 * <p>仓库里有 <b>32 个</b>类看起来是测试（文件名以 {@code Test} 结尾、里面逐条 {@code require(...)}），
 * 但它们是<b>手写自测类</b>：只有 {@code public static void main(String[])}，
 * <b>一个 {@code @Test} 都没有</b>。</p>
 *
 * <p>而 {@code RoadJUnitRegressionSuite} 是唯一被 Gradle 执行的入口，它按类名登记 JUnit 测试 ——
 * 这 32 个类不在其中，也永远不会被 JUnit 发现。也就是说，它们的
 * {@code require(...)} 只有在有人手工 {@code java -cp ... XxxTest} 时才会跑，
 * 而没有人会这么做。</p>
 *
 * <p>按包分布可以看清影响面：</p>
 * <ul>
 *   <li>{@code route} 11 个 —— 手绘路线编辑器、存档 Codec、预加载、平滑器（约 10,000 行）；</li>
 *   <li>{@code pathlogic/validation} 3 个 —— <b>道路数据校验与整条路网模型模拟</b>；
 *       其中 {@code RoadWholeFlowSimulationTest} 会「重放 2026-08-30 一致性门拒绝的 24 条路线」；</li>
 *   <li>{@code persistence/files} 3 个 —— 存档 IO、降级只读链、非损坏判定；</li>
 *   <li>{@code postprocess} 4 个 —— 并行走廊朝向、复用审计、端点 528 模拟、隧道口坡度；</li>
 *   <li>{@code runtime} 3 个 + {@code planning} 3 个 + {@code pathfinding} 2 个 +
 *       {@code pathlogic/grade} 2 个 + {@code pathlogic/structures} 1 个。</li>
 * </ul>
 *
 * <h2>为什么用反射调 main 而不是把它们改写成 JUnit</h2>
 * <p>改写的改动面很大：它们的断言是 {@code require} 而非 {@code assertEquals}，
 * 且大约一半的 {@code main} 自带 {@code SharedConstants.tryDetectVersion()} +
 * {@code Bootstrap.bootStrap()}、另一半没有。反射调用 {@code main} 是<b>零侵入</b>的：
 * 原文件一行不改，照样能被回归覆盖。</p>
 *
 * <p>前提条件（已核实）：这 32 个类<b>都没有 {@code System.exit}</b>，
 * 所以调用 {@code main} 不会杀掉 Gradle 的测试 JVM。</p>
 *
 * <h2>将来该怎么改</h2>
 * <p>如果要正式改写，正确做法是<b>一次一个类地搬</b>：搬完把该类从 {@link #SELF_TESTS} 删掉，
 * 让名单单调缩小。这样任何时刻都不会出现「搬了一半、两边都没跑」的窗口。</p>
 */
class SelfTestRunnerTest {

    /**
     * 需要接入的手写自测类（全限定名，穷举）。
     *
     * <p>新增此类自测时请一并登记，否则它又会变回「永远不跑」。
     * 判断口径：<b>测试目录下、有 {@code main}、没有 {@code @Test}/{@code @ParameterizedTest}</b>。
     * 有意排除两类：`RoadJUnitRegressionSuite`（套件入口本身）与
     * `tuning.RoadCenterlineAudit`（543 行的离线审计工具，不是断言式自测）。</p>
     */
    private static final String[] SELF_TESTS = {
            "net.shiroha233.roadweaver.pathfinding.WaterOnlyBridgePolicyTest",
            "net.shiroha233.roadweaver.pathfinding.sampling.opencl.CoarseLaneCountBoundaryTest",
            "net.shiroha233.roadweaver.pathlogic.grade.EndpointLockSimulationTest",
            "net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafetyPolicyTest",
            "net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceServiceTest",
            "net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidatorTest",
            "net.shiroha233.roadweaver.pathlogic.validation.RoadTopologyWaterSimulationTest",
            "net.shiroha233.roadweaver.pathlogic.validation.RoadWholeFlowSimulationTest",
            "net.shiroha233.roadweaver.persistence.files.CheckpointCacheTest",
            "net.shiroha233.roadweaver.persistence.files.IoDegradedReadOnlyChainTest",
            "net.shiroha233.roadweaver.persistence.files.IoNotCorruptionTest",
            "net.shiroha233.roadweaver.planning.ConnectionFallbackPolicyTest",
            "net.shiroha233.roadweaver.planning.path.PlannedReuseMatchTest",
            "net.shiroha233.roadweaver.planning.terrain.StructureClusterWindowTest",
            "net.shiroha233.roadweaver.postprocess.ParallelCorridorHeadingTest",
            "net.shiroha233.roadweaver.postprocess.ParallelCorridorReuseAuditTest",
            "net.shiroha233.roadweaver.postprocess.SavedEndpoint528SimulationTest",
            "net.shiroha233.roadweaver.postprocess.TunnelJunctionGradeSimulationTest",
            "net.shiroha233.roadweaver.route.AuditFixRegressionTest",
            "net.shiroha233.roadweaver.route.PlayerRoutePendingCountTest",
            "net.shiroha233.roadweaver.route.RouteAuditRound3Test",
            "net.shiroha233.roadweaver.route.RouteBuildProgressTest",
            "net.shiroha233.roadweaver.route.RouteCodecTest",
            "net.shiroha233.roadweaver.route.RoutePreloadCallSiteGuardTest",
            "net.shiroha233.roadweaver.route.RoutePreloadFallbackGuardTest",
            "net.shiroha233.roadweaver.route.RoutePreloadTimingTest",
            "net.shiroha233.roadweaver.route.RouteRoadDataPatcherTest",
            "net.shiroha233.roadweaver.route.RouteSegmentMapperTest",
            "net.shiroha233.roadweaver.route.RouteSmootherTest",
            "net.shiroha233.roadweaver.runtime.ChunkyPreheatFixRegressionTest",
            "net.shiroha233.roadweaver.runtime.PreflightExemptionGuardTest",
            "net.shiroha233.roadweaver.runtime.StartupHardwareProfileSmokeTest",
    };

    /**
     * 统一做一次 MC 引导。
     *
     * <p>32 个自测里大约一半的 {@code main} 自己做了
     * {@code SharedConstants.tryDetectVersion()} + {@code Bootstrap.bootStrap()}，
     * 另一半没做 —— 它们在手写环境里是「先跑别的自测顺带把引导做了」才能work。
     * 这里统一引导一次，让两类都能独立跑；两步都是幂等的，重复调用无害。
     * <b>顺序不能反</b>：只调 {@code bootStrap()} 会在 {@code Util.doFetchChoiceType} 抛
     * {@code ExceptionInInitializerError}。</p>
     */
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 名单里的每个自测类都必须存在且有main() {
        for (String fqcn : SELF_TESTS) {
            try {
                Class<?> type = Class.forName(fqcn);
                Method main = type.getMethod("main", String[].class);
                assertTrue(Modifier.isStatic(main.getModifiers()),
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
            fail("手写自测有失败（" + passed + "/" + SELF_TESTS.length + " 通过）：" + failures);
        }
        assertTrue(passed > 0, "至少要跑通一个自测，实际 " + passed);
    }
}