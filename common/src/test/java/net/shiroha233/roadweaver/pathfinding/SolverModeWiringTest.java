/* 文件职责：钉住 SolverMode 开关的接线 —— 2026-11-13 起新内核 BAND 是唯一生产实现。 */
package net.shiroha233.roadweaver.pathfinding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.shiroha233.roadweaver.bridge.AbObservingPathfinder;
import net.shiroha233.roadweaver.bridge.BandPathfinder;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig.PathfindingAlgorithm;
import net.shiroha233.roadweaver.pipeline.ab.SolverMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SolverMode 接线回归。
 *
 * <h2>2026-11-13 语义更新（旧 A* 系退役）</h2>
 * <p>用户拍板「全用最新的，旧的全部删除」。此后：</p>
 * <ol>
 *   <li>无论配置枚举写什么，工厂都只产出 {@link BandPathfinder}；</li>
 *   <li>枚举名（ASTAR_BASIC 等）保留仅为老配置的 GSON 解析不炸；</li>
 *   <li>AB 双跑仍可用，但 classic 侧也是 BandPathfinder（两臂同型，
 *       AB 观测只剩「新内核自身稳定性」的意义）。</li>
 * </ol>
 */
final class SolverModeWiringTest {

    private Object previous;

    @BeforeEach
    void remember() {
        previous = System.getProperty(SolverMode.PROPERTY);
        System.clearProperty(SolverMode.PROPERTY);
    }

    @AfterEach
    void restore() {
        if (previous == null) {
            System.clearProperty(SolverMode.PROPERTY);
        } else {
            System.setProperty(SolverMode.PROPERTY, previous.toString());
        }
    }

    @Test
    void noPropertyMeansBand() {
        assertEquals(SolverMode.CLASSIC, SolverMode.fromProperty(), "case=no-property-default");
        // 2026-11-13：CLASSIC 模式下也是新内核 —— 旧 A* 实现已退役。
        assertInstanceOf(BandPathfinder.class,
                PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD),
                "case=old-enum-yields-band");
    }

    @Test
    void propertyParsesEachMode() {
        System.setProperty(SolverMode.PROPERTY, "band");
        assertEquals(SolverMode.BAND, SolverMode.fromProperty(), "case=prop-band");
        System.setProperty(SolverMode.PROPERTY, "  AB  ");
        assertEquals(SolverMode.AB, SolverMode.fromProperty(), "case=prop-ab-trimmed");
        System.setProperty(SolverMode.PROPERTY, "nonsense");
        assertEquals(SolverMode.CLASSIC, SolverMode.fromProperty(), "case=prop-unknown-falls-back");
        System.setProperty(SolverMode.PROPERTY, "");
        assertEquals(SolverMode.CLASSIC, SolverMode.fromProperty(), "case=prop-empty-falls-back");
    }

    @Test
    void everyAlgorithmEnumYieldsBand() {
        // 旧枚举名只是配置兼容层：全部映射到新内核。
        for (PathfindingAlgorithm algo : PathfindingAlgorithm.values()) {
            Pathfinder byDecision = PathfinderFactory.decide(algo, SolverMode.CLASSIC);
            assertInstanceOf(BandPathfinder.class, byDecision,
                    "case=legacy-enum-maps-to-band-" + algo.name());
        }
    }

    @Test
    void bandModeTakesOverRegardlessOfAlgorithm() {
        assertInstanceOf(BandPathfinder.class,
                PathfinderFactory.decide(PathfindingAlgorithm.POTENTIAL_FIELD, SolverMode.BAND),
                "case=band-overrides-algorithm");
        assertInstanceOf(BandPathfinder.class,
                PathfinderFactory.decide(PathfindingAlgorithm.ASTAR_BASIC, SolverMode.BAND),
                "case=band-overrides-astar");
    }

    @Test
    void abModeWrapsTheClassicAlgorithm() {
        Pathfinder ab = PathfinderFactory.decide(PathfindingAlgorithm.ASTAR_BASIC, SolverMode.AB);
        assertInstanceOf(AbObservingPathfinder.class, ab, "case=ab-wraps");

        Pathfinder viaCreate;
        System.setProperty(SolverMode.PROPERTY, "ab");
        viaCreate = PathfinderFactory.create(PathfindingAlgorithm.ASTAR_BASIC);
        assertInstanceOf(AbObservingPathfinder.class, viaCreate, "case=ab-via-create");
    }

    @Test
    void abNeverClaimsToBeEffective() {
        assertTrue(SolverMode.AB.runsClassic(), "case=ab-runs-classic");
        assertTrue(SolverMode.AB.runsBand(), "case=ab-runs-band");
        assertEquals(SolverMode.CLASSIC, SolverMode.AB.effective(), "case=ab-effective-is-classic");
        assertEquals(SolverMode.BAND, SolverMode.BAND.effective(), "case=band-effective-is-band");
        assertEquals(SolverMode.CLASSIC, SolverMode.CLASSIC.effective(), "case=classic-effective-is-classic");
        // classic 侧也是 BandPathfinder（旧实现已删，AB 只剩同型对照）。
        assertInstanceOf(BandPathfinder.class,
                PathfinderFactory.createClassic(PathfindingAlgorithm.ASTAR_BASIC),
                "case=classic-side-is-band");
    }
}
