/* 文件职责：钉住 SolverMode 开关的接线 —— 默认必须走旧链，AB 绝不改变输出。 */
package net.shiroha233.roadweaver.pathfinding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.shiroha233.roadweaver.bridge.AbObservingPathfinder;
import net.shiroha233.roadweaver.bridge.BandPathfinder;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig.PathfindingAlgorithm;
import net.shiroha233.roadweaver.pathfinding.impl.BasicAStarPathfinder;
import net.shiroha233.roadweaver.pathfinding.impl.PotentialFieldPathfinder;
import net.shiroha233.roadweaver.pipeline.ab.SolverMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SolverMode 接线回归。
 *
 * <p>核心不变式（这三条任意一条破了都等于「静默换了算法」）：</p>
 * <ol>
 *   <li>没设系统属性 ⇒ 走旧链，且与重构前的实例类型完全一致；</li>
 *   <li>设了不认识的值 ⇒ 回落 CLASSIC，不许抛异常、不许换算法；</li>
 *   <li>AB 模式下 classic 侧仍按 algorithm 建（band 只是旁挂观察）。</li>
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
    void noPropertyMeansClassic() {
        assertEquals(SolverMode.CLASSIC, SolverMode.fromProperty(), "case=no-property-default");
        assertInstanceOf(PotentialFieldPathfinder.class,
                PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD),
                "case=no-property-keeps-old-chain");
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
    void classicModeKeepsEveryAlgorithmIntact() {
        for (PathfindingAlgorithm algo : PathfindingAlgorithm.values()) {
            Pathfinder byDecision = PathfinderFactory.decide(algo, SolverMode.CLASSIC);
            Pathfinder byCreate = PathfinderFactory.createClassic(algo);
            assertEquals(byCreate.getClass(), byDecision.getClass(),
                    "case=classic-passthrough-" + algo.name());
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
        assertInstanceOf(BasicAStarPathfinder.class,
                PathfinderFactory.createClassic(PathfindingAlgorithm.ASTAR_BASIC),
                "case=classic-astar-type");
    }
}