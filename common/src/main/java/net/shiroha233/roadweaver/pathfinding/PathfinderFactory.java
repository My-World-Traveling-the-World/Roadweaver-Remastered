/* 文件职责：寻路器工厂 —— 按算法枚举建实例，并按 SolverMode 决定是否走新链/双跑。 */
package net.shiroha233.roadweaver.pathfinding;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig.PathfindingAlgorithm;
import net.shiroha233.roadweaver.pipeline.ab.SolverMode;
import net.shiroha233.roadweaver.bridge.AbObservingPathfinder;
import net.shiroha233.roadweaver.bridge.BandPathfinder;

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
        // 2026-11-13：旧 A* 系全部退役（用户拍板）。无论枚举写什么，
        // 都返回新内核 —— 枚举名暂留仅为老配置的 GSON 解析不炸。
        return new BandPathfinder();
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