/* 文件职责：单条路线的求解接口 —— 新旧两套算法实现同一个契约，才能对比。 */
package net.shiroha233.roadweaver.pipeline.ab;

import java.util.List;

import net.shiroha233.roadweaver.topology.RoadNode;

/**
 * 单条路线的求解器。
 *
 * <h2>为什么对比单位是「单条路线」而不是「整个路网」</h2>
 * <p>旧链是<b>逐条连接</b>规划的；新链是<b>先定拓扑再画几何</b>，天然按路网做。
 * 直接用路网对比会变成苹果比橘子。</p>
 *
 * <p>所以对比收到<b>单条路线</b>：(from, to) 两个端点 → 一串点。
 * 新链可以只对一条边跑「度量 + 回溯 + 曲率平滑」，旧链本来就是这个粒度。
 * 这才是公平的同局对比。</p>
 */
public interface RouteSolver {

    /** 本求解器对应的模式（用于报告）。 */
    SolverMode mode();

    /**
     * 求解一条路线。
     *
     * @param from 起点
     * @param to   终点
     * @return 求解结果（不可达时 {@code reachable=false}、{@code points} 为空）
     */
    RouteSolution solve(RoadNode from, RoadNode to);

    /**
     * 一条路线的求解结果。
     *
     * @param points    点序列 {@code {x, z}}；不可达时为空
     * @param metrics   中立度量
     * @param reachable 是否可达
     */
    record RouteSolution(List<int[]> points, RouteMetrics metrics, boolean reachable) {

        public RouteSolution {
            points = List.copyOf(points);
        }

        /** 不可达结果。 */
        public static RouteSolution unreachable() {
            return new RouteSolution(List.of(), RouteMetrics.from(new int[0], new int[0]), false);
        }
    }
}
