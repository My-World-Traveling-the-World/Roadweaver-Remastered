/* 文件职责：寻路器工厂 —— 按算法枚举建实例，并按 SolverMode 决定是否走新链/双跑。 */
package net.shiroha233.roadweaver.pathfinding;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig.PathfindingAlgorithm;
import net.shiroha233.roadweaver.pipeline.ab.SolverMode;
import net.shiroha233.roadweaver.bridge.AbObservingPathfinder;
import net.shiroha233.roadweaver.bridge.BandPathfinder;
import net.shiroha233.roadweaver.pathfinding.impl.BasicAStarPathfinder;
import net.shiroha233.roadweaver.pathfinding.impl.BidirectionalAStarPathfinder;
import net.shiroha233.roadweaver.pathfinding.impl.GradientDescentPathfinder;
import net.shiroha233.roadweaver.pathfinding.impl.PotentialFieldPathfinder;

/**
 * 寻路器工厂。
 *
 * <h2>两层开关，各管一件事</h2>
 * <ul>
 *   <li>{@code pathfindingAlgorithm}（配置键）—— 决定<b>用哪个旧算法</b>；</li>
 *   <li>{@link SolverMode}（系统属性 {@code -Droadweaver.solverMode}）—— 决定
 *       <b>要不要让新三层内核参与</b>：CLASSIC 不参与、BAND 由它接管、AB 双跑观察。</li>
 * </ul>
 *
 * <h2>★ 出厂行为必须与 0.9.098 逐字节一致</h2>
 * <p>没设系统属性时 {@code SolverMode.fromProperty()} 回落 CLASSIC，
 * 本工厂返回的对象与重构前<b>完全相同</b>。这一点由
 * {@code SolverModeWiringTest} 钉住。</p>
 */
public final class PathfinderFactory {

    private PathfinderFactory() {}

    /** 只按算法枚举建实例 —— 不含 SolverMode 的影响。 */
    public static Pathfinder createClassic(PathfindingAlgorithm algorithm) {
        return switch (algorithm) {
            case ASTAR_BASIC -> new BasicAStarPathfinder();
            case ASTAR_BIDIRECTIONAL -> new BidirectionalAStarPathfinder();
            case GRADIENT_DESCENT -> new GradientDescentPathfinder();
            case POTENTIAL_FIELD -> new PotentialFieldPathfinder();
            // 新架构（2026-11-12）：Finsler 度量 + Fast Marching + 弹性带。
            // 构造在 bridge 层 —— 那是唯一同时认识 foundation 与求解层的地方。
            case BAND -> new BandPathfinder();
        };
    }

    /** 生产入口：算法枚举 + SolverMode 一起决定最终实例。 */
    public static Pathfinder create(PathfindingAlgorithm algorithm) {
        return decide(algorithm, SolverMode.fromProperty());
    }

    /**
     * 纯函数形式的决策，便于单测（不需要 MC 运行时）。
     *
     * <p>AB 模式下 classic 侧仍按 {@code algorithm} 建 —— 所以
     * 「algorithm=POTENTIAL_FIELD + mode=AB」跑的是「势场 + 新链双跑」。</p>
     */
    public static Pathfinder decide(PathfindingAlgorithm algorithm, SolverMode mode) {
        SolverMode m = mode == null ? SolverMode.CLASSIC : mode;
        return switch (m) {
            case CLASSIC -> createClassic(algorithm);
            case BAND -> new BandPathfinder();
            case AB -> new AbObservingPathfinder(createClassic(algorithm), new BandPathfinder());
        };
    }
}