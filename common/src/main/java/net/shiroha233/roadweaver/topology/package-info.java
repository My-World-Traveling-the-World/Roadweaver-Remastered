/* 文件职责：决定「哪些节点相连」——在测地距离矩阵上求最省连接集，保证边不相交。 */
/**
 * 拓扑层：最小生成树 / Steiner 树。
 *
 * <h2>为什么需要这一层</h2>
 * <p>旧实现让每条连接<b>各自独立</b>规划，然后事后用三套检测器去发现并处理
 * 「并行」「交叉」「麻花」：{@code parallel corridor}、{@code braid}、
 * {@code ExistingRoadJunctionService.mergeRoads}。</p>
 *
 * <p>真机实测（2026-11-12，存档 {@code 20261112 1555}）：{@code parallel corridor}
 * 26 次尝试<b>全部被拒</b>、{@code braidPairs=0}、{@code braidScanMs=0} ——
 * 三套检测器等于没工作，于是并行段、环、spur 全都留在世界里。</p>
 *
 * <p>根因不是检测器写得不好，而是<b>顺序错了</b>：边一旦先各自画好，
 * 就只能事后修补。正确顺序是<b>先定拓扑</b>——树的边天然不相交，
 * 那些检测器检查的问题在拓扑层根本不存在。</p>
 *
 * <h2>输入 / 输出</h2>
 * <ul>
 *   <li>输入：{@code metric} 层算出的测地距离矩阵（N 个结构节点两两可达距离）。</li>
 *   <li>输出：一个不相交的边集（MST 或 Steiner 树 + 可选的环路边）。</li>
 * </ul>
 *
 * <p>本包只允许依赖 {@code metric} 与 {@code foundation}。
 * <b>禁止</b>依赖 {@code band} / {@code pipeline}。</p>
 *
 * @see net.shiroha233.roadweaver.metric
 */
package net.shiroha233.roadweaver.topology;
