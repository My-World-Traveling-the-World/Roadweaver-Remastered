/* 文件职责：定义道路的「度量」——把长度、坡度、障碍折成一个可求测地线的代价。 */
/**
 * 度量层：Finsler 度量 + 快速行进（Fast Marching）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>旧实现让 A* 在 8 邻域网格上同时决定「走哪条路」和「长什么形状」。
 * 8 邻域只有 8 个可选方向（45° 量化），所以锯齿是<b>结构性内生</b>的，
 * 只能靠后处理（DP 简化 → 样条 → 平滑 → relax → 锯齿检测）一段段去抹，
 * 每抹一次就多一个 artifact。</p>
 *
 * <p>正确的做法是把「度量」独立出来：代价只依赖<b>位置与方向</b>，
 * 与曲线怎么参数化无关 —— 这在数学上是 Finsler 度量，测地线有确定的解，
 * 而且求解器（Fast Marching）输出的方向是<b>连续</b>的，没有 45° 量化。</p>
 *
 * <h2>度量形式</h2>
 * <pre>
 * c(x, θ̂) = 1 + λ_grade·(∇h(x)·θ̂)² + λ_obs·V(x)
 * </pre>
 * <ul>
 *   <li>{@code ∇h(x)·θ̂} —— 沿该方向的地形坡度（<b>按方向</b>，不是按步数）。
 *       旧实现「坡度罚按步收而基础代价按长度收」导致正交步与对角步差 38 倍，
 *       在这个形式里根本不会出现：坡度本来就是按弧长积分的。</li>
 *   <li>{@code V(x)} —— 障碍势，由距离变换生成（精确，不是近似）。</li>
 * </ul>
 *
 * <h2>依赖方向（由 ArchitectureGuardTest 强制）</h2>
 * <p>本包只允许依赖 {@code foundation} 与 {@code api}。
 * <b>禁止</b>依赖 {@code topology} / {@code band} / {@code pipeline}。</p>
 *
 * @see net.shiroha233.roadweaver.topology
 * @see net.shiroha233.roadweaver.band
 */
package net.shiroha233.roadweaver.metric;
