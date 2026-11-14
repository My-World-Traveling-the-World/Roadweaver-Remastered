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
 *   <li>{@code pathfindingAlgorithm}（配置键）—— <b>2026-11-13 起只是 GSON 兼容键</b>：
 *       四个旧 A* 寻路器（BasicAStar / Bidirectional / GradientDescent / PotentialField）
 *       已全部删除，枚举里只剩 {@code BAND}，任何旧值都由 {@code sanitize()} 归一它。
 *       它不再「决定用哪个算法」；</li>
 *   <li>{@link SolverMode}（系统属性 {@code -Droadweaver.solverMode}）—— 决定
 *       <b>要不要让新三层内核参与</b>：CLASSIC 不参与、BAND 由它接管。</li>
 *   <li><b>AB 双跑已停用（2026-11-14，审计 G）</b>：旧 A* 全部删除后 classic 侧
 *       也是 {@link BandPathfinder}，两臂同型 ⇒ 双跑只会白算一倍并打出假对比。
 *       枚举值 {@code AB} 仍可解析（老配置不炸），但 {@link AbObservingPathfinder}
 *       检测到同型即短路、只跑一次，并打一次 WARN 说明原因。</li>
 * </ul>
 *
 * <h2>★ 当前行为：CLASSIC 也只是兼容键，不代表旧算法</h2>
 * <p>旧 A* 已全部删除。没设系统属性时 {@code SolverMode.fromProperty()} 回落 CLASSIC，
 * 但 {@link #createClassic} 仍返回 {@link BandPathfinder}；因此不能把 CLASSIC 理解成
 * 「与 0.9.098 逐字节一致」。这一点由 {@code SolverModeWiringTest} 钉住。</p>
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
     * <p>AB 模式下 classic 侧虽仍接收 {@code algorithm}，但工厂已统一返回 BAND；
     * 两臂同型时 {@link AbObservingPathfinder} 会短路，不再产生「旧算法 vs 新链」的假对照。</p>
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