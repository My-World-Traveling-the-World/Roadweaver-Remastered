package net.shiroha233.roadweaver.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 外部 mod 对 RoadWeaver 的<b>反射契约</b>守卫测试。
 *
 * <h2>为什么必须有这个测试</h2>
 * <p>工作区内有 3 个 mod 通过<b>纯反射 + 硬编码全限定名</b>调 RoadWeaver
 * （它们刻意不建立编译期依赖）：</p>
 *
 * <table border="1">
 *   <caption>外部反射契约清单</caption>
 *   <tr><th>外部 mod</th><th>调用点</th><th>目标</th></tr>
 *   <tr><td>tanshugetrees</td>
 *       <td>{@code bridge/PlainCraftRoadReservation.java:34}</td>
 *       <td>{@code features.path.pathlogic.core.HugeTreeReservation.reserve(...)}</td></tr>
 *   <tr><td>plaincraft-great-wall</td>
 *       <td>{@code mixin/RiversPrewarmedMixin.java:49}</td>
 *       <td>{@code generation.InitialGenManager}（{@code @Mixin targets}）</td></tr>
 *   <tr><td>streams-reflowing</td>
 *       <td>{@code mixin/RoadWeaverInitialGenerationStreamsBoostMixin.java:11}</td>
 *       <td>{@code generation.InitialGenManager}（{@code @Mixin targets}）</td></tr>
 *   <tr><td>plaincraft-chunky-autogen</td>
 *       <td>{@code ChunkyRoadAwareCoordinator.java:723}</td>
 *       <td>{@code planning.RoadPlanningReadinessIndex}（{@code Class.forName}）</td></tr>
 * </table>
 *
 * <h2>这些失效是静默的</h2>
 * <ul>
 *   <li>tanshugetrees 那侧是 {@code catch (Throwable)} + 只打一次 debug：
 *       类名对不上不报错，只是「巨树占地预留」失效，道路重新穿树。</li>
 *   <li>mixin 的 {@code targets} 找不到类时按 {@code require} 语义决定：
 *       {@code require = 0} 静默跳过，等于注入从未发生。</li>
 *   <li>{@code Class.forName} 失败通常被 {@code catch} 成「mod 不存在」。</li>
 * </ul>
 *
 * <p>所以任何包重构都必须先跑这个测试。0.9.087 结构重构就真的踩了一次：
 * {@code HugeTreeReservation} 被移到 {@code pathlogic.construction}，
 * 已通过保留 {@code features.path.pathlogic.core.HugeTreeReservation} 兼容壳修复，
 * 本测试即为那次事故留下的钉子。</p>
 */
class ExternalReflectionContractTest {

    /**
     * tanshugetrees 现在用的标准 API 入口。
     *
     * <p>改造前它反射的是 {@code features.path.pathlogic.core.HugeTreeReservation} ——
     * 那个旧 FQN 已在 tanshugetrees 改用 {@code StructureReservationApi} 之后
     * <b>整体删除</b>。本测试改为钉住「标准 API 入口必须存在」，
     * 并额外断言旧 FQN <b>确实已经不在了</b>（防止有人把它当兼容壳又加回来）。</p>
     */
    private static final String TANSHUGETREES_TARGET =
            "net.shiroha233.roadweaver.api.incoming.StructureReservationApi";

    /** 已被删除的旧反射目标：不应再出现。 */
    private static final String RETIRED_TANSHUGETREES_TARGET =
            "net.shiroha233.roadweaver.features.path.pathlogic.core.HugeTreeReservation";

    /** great-wall 与 streams-reflowing 的 mixin 目标。 */
    private static final String INITIAL_GEN_TARGET =
            "net.shiroha233.roadweaver.generation.InitialGenManager";

    /** chunky-autogen 的 Class.forName 目标。 */
    private static final String READINESS_INDEX_TARGET =
            "net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex";

    @Test
    void tanshugetreesReflectionTargetStillExists() {
        Class<?> type = assertDoesNotThrow(() -> Class.forName(TANSHUGETREES_TARGET),
                "tanshugetrees 反射的类名必须仍然存在，否则巨树预留会静默失效");
        assertNotNull(type);
    }

    @Test
    void tanshugetreesReserveSignatureStillMatches() {
        assertDoesNotThrow(() -> {
            Class<?> type = Class.forName(TANSHUGETREES_TARGET);
            var method = type.getMethod("reserve",
                    ServerLevel.class, String.class, BlockPos.class, int[].class, int.class);
            // 标准 API 用 boolean 回报是否登记成功（原来反射调的是 void，调用方无从知道）。
            assertEquals(boolean.class, method.getReturnType());
            assertEquals(5, method.getParameterCount());
        }, "StructureReservationApi.reserve 的签名必须保持 (ServerLevel, String, BlockPos, int[], int) -> boolean");
    }

    @Test
    void retiredReflectionTargetIsReallyGone() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(RETIRED_TANSHUGETREES_TARGET),
                "旧反射目标必须已删除。若它又被加回来，说明有人在维持一条本该消失的兼容路径 —— "
                        + "那正是 api.interop 那次失败的形态。");
    }

    @Test
    void realReservationImplementationStillExists() {
        // 标准 API 背后的真身必须存在（api 只是门面）
        assertDoesNotThrow(() -> Class.forName(
                "net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation"));
    }

    @Test
    void mixinTargetsStillExist() {
        assertDoesNotThrow(() -> Class.forName(INITIAL_GEN_TARGET),
                "great-wall 与 streams-reflowing 的 @Mixin targets 必须仍然存在");
    }

    @Test
    void chunkyAutogenReflectionTargetStillExists() {
        assertDoesNotThrow(() -> Class.forName(READINESS_INDEX_TARGET),
                "chunky-autogen 的 Class.forName 目标必须仍然存在");
    }
}
