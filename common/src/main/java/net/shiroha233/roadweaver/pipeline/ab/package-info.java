/* 文件职责：新老两套求解算法的并存、切换与同局对比（AB）。 */
/**
 * AB 双跑层。
 *
 * <h2>为什么需要它</h2>
 * <p>换算法最危险的一点是「换完发现更差，但已经不知道旧的是什么样」。
 * 本层让两套算法在<b>同一个 jar 里并存</b>，按配置切换，或同时跑并输出逐项对比。</p>
 *
 * <h2>怎么做到「不改旧链也能测」</h2>
 * <p>两边输出的数据结构完全不同：旧链给 {@code List<RoadData>}（站序列），
 * 新链给 {@code RoadNetworkPlan}（曲线）。硬要统一就会牵动旧链。</p>
 *
 * <p>所以本层引入一个<b>中立的度量</b> {@link RouteMetrics}：它只吃一串点
 * {@code (x, z)}，算出长度、重复 XZ、曲率能量、最大转角。
 * 旧链只要把站坐标喂进来、新链把曲线喂进来，就得到<b>同一把尺子</b>。</p>
 *
 * <h2>切换语义</h2>
 * <ul>
 *   <li>{@link SolverMode#CLASSIC} —— 走旧链，行为与 0.9.098 完全一致（出厂默认）</li>
 *   <li>{@link SolverMode#BAND} —— 走新的三层内核</li>
 *   <li>{@link SolverMode#AB} —— 两套都跑，输出逐项对比，<b>但不生效</b>（只观察）</li>
 * </ul>
 *
 * <p><b>出厂默认是 CLASSIC</b> —— 换算法必须是显式选择，不能悄悄生效。
 * 这条和「功能不丢失」是同一件事。</p>
 *
 * @see SolverMode
 * @see RouteMetrics
 * @see AbRunner
 */
package net.shiroha233.roadweaver.pipeline.ab;
