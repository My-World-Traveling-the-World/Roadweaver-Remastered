/* 文件职责：钉住「已有公路探针必须排除当前路自身」这条安全前提。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已有公路探针测试（2026-11-15）。
 *
 * <h2>为什么这条安全前提必须有测试</h2>
 * <p>{@link ExistingRoadProbe} 的全部价值在于「查得到别的路、查不到自己」。
 * 一旦自排除失效，后果不是日志难看，而是<b>整条走廊被判成全站禁地面</b>：</p>
 * <pre>
 *   rerouteAvoiding → regenerateRoadWithoutPersist
 *     → 剖面计算时当前路的旧几何已经在存储里
 *       → 不排除自己 ⇒ 每一站都「已被占用」⇒ DP 只能全程桥/隧道
 * </pre>
 * <p>而这个失败模式在实验室里完全看不见（需要真实存档与重规划路径），
 * 所以至少要把它拆成可单测的判断逻辑钉住。</p>
 *
 * <h2>局限（如实说明）</h2>
 * <p>完整的 {@code forCorridor} 需要 {@code ServerLevel} 与真实存储，
 * 无法在纯 JUnit 里跑。本测试覆盖两件事：
 * ①<b>自排除的判定逻辑</b>（含端点顺序颠倒的情况）；
 * ②<b>退化输入必须返回「查不到」</b>——保守方向是「不禁地面」，
 * 因为误禁会让整条路无法铺地面，比漏禁更糟。</p>
 */
class ExistingRoadProbeTest {

    /**
     * {@code new RoadData(...)} 会触发静态 CODEC，需要先引导 MC 注册表。
     * 顺序不能反：先 {@code tryDetectVersion}（给出 MC 版本），再 {@code bootStrap}。
     * 只调后者会在 {@code Util.doFetchChoiceType} 抛 ExceptionInInitializerError。
     * 参照套件里已可用的 RoadChunkPlanCompilerReuseOwnershipTest。
     */
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }


    /** 造一个只带 owner 键的 RoadData —— 其余字段本测试用不到。 */
    private static net.shiroha233.roadweaver.core.model.RoadData roadWithOwners(long a, long b) {
        return new net.shiroha233.roadweaver.core.model.RoadData(
                3, 0, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), a, b);
    }

    @Test
    void 端点键完全相同的路被认成自身() {
        var self = roadWithOwners(111L, 222L);
        assertTrue(ExistingRoadProbe.isSelf(self, 111L, 222L),
                "同一条路的端点键必须被识别为自身 —— 否则重规划时整条走廊会被自己禁掉");
    }

    @Test
    void 端点顺序颠倒也认成自身() {
        // 存储里的写入顺序由写入方决定，A→B 与 B→A 是同一对端点
        var self = roadWithOwners(222L, 111L);
        assertTrue(ExistingRoadProbe.isSelf(self, 111L, 222L),
                "端点顺序颠倒仍然是同一条路 —— 不认会导致自排除漏掉一半情况");
    }

    @Test
    void 只有一端相同不算自身() {
        var other = roadWithOwners(111L, 999L);
        assertFalse(ExistingRoadProbe.isSelf(other, 111L, 222L),
                "只共享一个端点的是另一条路（路网里端点共用很常见），不能误判成自身");
    }

    @Test
    void 完全不同的端点不算自身() {
        var other = roadWithOwners(333L, 444L);
        assertFalse(ExistingRoadProbe.isSelf(other, 111L, 222L));
    }

    @Test
    void 世界为空时返回查不到而不是崩() {
        var probe = ExistingRoadProbe.forCorridor(null, java.util.List.of(), 1L, 2L);
        assertFalse(probe.hasOtherRoadAt(0, 0),
                "世界为空必须返回「查不到」—— 保守方向是不禁地面");
    }

    @Test
    void 中心线为空时返回查不到() {
        var probe = ExistingRoadProbe.forCorridor(null, null, 1L, 2L);
        assertFalse(probe.hasOtherRoadAt(100, 200));
    }
}
