/* 文件职责：适配器层 —— 把 foundation 的现实接口接到求解层的抽象端口上。 */
/**
 * 桥接层（Adapters）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>求解层（{@code metric} / {@code topology} / {@code band}）通过<b>抽象端口</b>
 * 拿输入：{@code metric.TerrainField}、{@code metric.ObstacleField}。
 * 而现实里的地形来自 {@code pathfinding.sampling.AccurateHeightSampler}，
 * 障碍来自 {@code foundation.constraint} 的避让判据。</p>
 *
 * <p>如果让 {@code terrain} 包直接实现 {@code metric.TerrainField}，那就是
 * <b>foundation → solver</b> 的反向依赖 —— 求解层一改，foundation 全部要跟着改，
 * 这正是旧仓包语义崩掉的成因之一。</p>
 *
 * <p>所以把「接线」这件事单独放到本层：</p>
 * <pre>
 *   foundation  ──►（不认识求解层）
 *   metric/topology/band  ──►（认识的是端口，不是实现）
 *            ▲
 *            │ 只有 bridge 同时认识两边
 *         bridge
 * </pre>
 *
 * <h2>依赖方向（由 ArchitectureGuardTest 强制）</h2>
 * <p>本包可以依赖 foundation 与所有求解层。
 * <b>但任何求解层都不许反过来 import 本包</b> —— 那会把适配器固化成算法的一部分，
 * 也就再也换不掉实现。</p>
 *
 * @see net.shiroha233.roadweaver.metric.TerrainField
 * @see SampledTerrainField
 */
package net.shiroha233.roadweaver.bridge;
