/* 文件职责：按顺序编排求解阶段，并用质量门守住每一步不许比上一步差。 */
/**
 * 编排层：阶段状态机 + 质量门（QualityGate）。
 *
 * <h2>为什么需要质量门</h2>
 * <p>旧实现里 {@code RoadQualityMetrics} 的 javadoc 已经写着
 * 「every number on it is a regression guard」，但那些数字<b>只打日志、
 * 从不参与决策</b>。结果是：{@code fine regenerate}（step=2 整条重跑）
 * 把 17 条路里的 8 条变长（最多 +42.7%）、9 条 {@code dupXz} 增加
 * （最多 2 → 18），全部被无条件接受。</p>
 *
 * <p>质量门的作用就是把「已经算好的指标」接到「接受 / 回退」的决策上：
 * 任何阶段的产物如果比输入更差，就回退保留上一版，并打出拒绝原因。</p>
 *
 * <h2>阶段顺序</h2>
 * <pre>
 * 结构扫描 → 连接生成 → 【村庄端点筛选（在 foundation，早于一切几何）】
 *   → metric（度量 + 场求解）
 *   → topology（MST / Steiner）
 *   → band（曲率平滑）
 *   → 【纵断面（foundation）】→ 【施工（foundation）】
 * </pre>
 *
 * <p>本包可以依赖所有求解层；<b>求解层不许反过来依赖本包</b>。</p>
 *
 * @see net.shiroha233.roadweaver.band
 */
package net.shiroha233.roadweaver.pipeline;
