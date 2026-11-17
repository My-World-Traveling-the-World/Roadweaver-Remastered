/* 文件职责：AB 双跑包装器 —— 旧链生效、新链只观察，永不改变输出。 */
package net.shiroha233.roadweaver.bridge;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.ArrayList;
import java.util.List;

import net.shiroha233.roadweaver.pipeline.ab.AbRunner;
import net.shiroha233.roadweaver.pipeline.ab.RouteMetrics;
import net.shiroha233.roadweaver.pipeline.ab.RouteSolver;

/**
 * AB 双跑包装器：**旧链生效，新链只观察**。
 *
 * <h2>它在 AB 模式下的唯一职责</h2>
 * <p>先用旧链算出真实结果（这个结果原样返回），再让新链在同一组输入上跑一遍，
 * 把「两边差多少」记成一行日志。<b>新链的结果永远不生效。</b></p>
 *
 * <h2>★ 三道保护，保证观察不会伤害生产</h2>
 * <ol>
 *   <li>新链整段包在 try/catch(Throwable) 里 —— 新算法抛任何异常都只记一行 WARN，
 *       旧链结果照常返回。观察代码绝不允许把主流程带下水。</li>
 *   <li>比较结果只走日志，不进任何返回值、不进任何缓存。</li>
 *   <li>日志节流：每 N 次只打一行，避免刷屏拖慢规划。</li>
 * </ol>
 *
 * <h2>★ 为什么 AB 模式下 band 结果不能生效</h2>
 * <p>AB 存在的目的是「安全地看新算法表现」。如果它在 AB 下生效，
 * 那这个模式就没法用来观察了 —— 观察者不能改变被观察的系统。</p>
 */
public final class AbObservingPathfinder implements Pathfinder {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /** 每多少次只在日志里报一行（避免逐边刷屏）。 */
    private static final int LOG_EVERY = 16;

    private final Pathfinder classic;
    private final Pathfinder band;
    private final java.util.concurrent.atomic.AtomicLong calls =
            new java.util.concurrent.atomic.AtomicLong();

    /** 「两臂其实是同一个类」的告警只打一次。 */
    private final java.util.concurrent.atomic.AtomicBoolean identicalWarned =
            new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * 两臂是否为同一个实现类。
     *
     * <p><b>2026-11-14（审计 G）</b>：四个旧 A* 寻路器删除后，
     * {@code PathfinderFactory.createClassic} 也返回 {@link BandPathfinder} ——
     * 于是 AB 模式的 classic 与 band 两臂是<b>同一个类</b>，跑两遍得到逐字节相同的
     * 结果。此时再打 {@code [ROAD-AB] verdict=...} 是假对比：既白花一倍算力，
     * 又会让读日志的人以为「新链和旧链有差异」。</p>
     *
     * <p>所以检测到同型时直接短路：只跑一次、不打对比日志，并用一条 WARN 说清原因。</p>
     */
    private final boolean armsIdentical;

    public AbObservingPathfinder(Pathfinder classic, Pathfinder band) {
        if (classic == null) throw new IllegalArgumentException("classic 不能为 null");
        this.classic = classic;
        this.band = band;
        this.armsIdentical = band != null && classic.getClass() == band.getClass();
    }

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, int width,
                               ServerLevel level, int maxSteps,
                               TerrainSamplingCache cache, PathTerrainField terrain,
                               PathfindingCostConfig config) {
        PathResult authoritative = classic.findPath(start, end, width, level, maxSteps,
                cache, terrain, config);
        if (band == null) return authoritative;

        // 2026-11-14（审计 G）：两臂同型 ⇒ 双跑毫无信息量，直接短路。
        if (armsIdentical) {
            if (identicalWarned.compareAndSet(false, true)) {
                LOGGER.warn("[ROAD-AB] AB 双跑已停用：两臂都是 {} —— 旧 A* 已全部删除，"
                        + "classic 侧不再存在独立实现。请把 solverMode 设为 BAND（或留空用默认）。"
                        + "本条只提示一次。", classic.getClass().getName());
            }
            return authoritative;
        }

        long n = calls.incrementAndGet();
        boolean log = (n % LOG_EVERY) == 1L;
        try {
            PathResult observed = band.findPath(start, end, width, level, maxSteps,
                    cache, terrain, config);
            if (log) {
                // 2026-11-13：这里原来只打「点数 + 是否成功」，信息量太低 ——
                // 点数是 500 还是 480 根本说明不了新链画得好不好。改用 AbRunner
                // 的四项中立度量（长度 / 重复 XZ / 曲率能量 / 最大转角）+ verdict。
                // 这同时让 AbRunner 从「只有测试在用」变成生产接线。
                AbRunner.AbReport report = AbRunner.compare(
                        "(" + start.getX() + "," + start.getZ() + ")->("
                                + end.getX() + "," + end.getZ() + ")",
                        toSolution(authoritative), toSolution(observed));
                LOGGER.info("【新旧选线诊断对照结果】 " + "[ROAD-AB] #{} verdict={}\n{}",
                        n, report.verdict(), String.join("\n", report.diffs()));
            }
        } catch (Throwable t) {
            if (log) {
                LOGGER.warn("[ROAD-AB] #{} 新链观察失败（已忽略，旧链结果照常生效）：{}", n, t.toString());
            }
        }
        return authoritative;
    }
    /**
     * 把旧链的 {@link PathResult} 翻译成中立度量要的形状。
     *
     * <p>只取 x/z —— {@link RouteMetrics} 是二维度量，高度不参与比较：
     * 纵断面由另一套机制负责，混进来会让「长度」这项失去可比性。</p>
     */
    private static RouteSolver.RouteSolution toSolution(PathResult r) {
        if (r == null || !r.success() || r.rawPath() == null || r.rawPath().isEmpty()) {
            return RouteSolver.RouteSolution.unreachable();
        }
        List<int[]> pts = new ArrayList<>(r.rawPath().size());
        for (BlockPos p : r.rawPath()) {
            if (p != null) {
                pts.add(new int[]{p.getX(), p.getZ()});
            }
        }
        if (pts.isEmpty()) {
            return RouteSolver.RouteSolution.unreachable();
        }
        return new RouteSolver.RouteSolution(pts, RouteMetrics.fromPairs(pts), true);
    }
}