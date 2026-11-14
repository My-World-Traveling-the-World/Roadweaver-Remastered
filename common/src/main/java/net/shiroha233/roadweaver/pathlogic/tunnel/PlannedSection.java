/* 文件职责：隧道分段的规划结果 —— 从 RoadTunnelBuilder 抽出以控制单文件规模。 */
package net.shiroha233.roadweaver.pathlogic.tunnel;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 一个隧道分段的规划结果（2026-11-15 从 {@link RoadTunnelBuilder} 抽出）。
 *
 * <p><b>为什么搬到独立文件：</b>{@code RoadTunnelBuilder} 已到 1002 行，
 * 超出「单文件不超过 1000 行」的约束。本 record 是纯数据载体，
 * 与建造流程职责不同。**跨包引用有 3 处，需要各自补 import。**</p>
 *
 * <h2>五个列表的分工（不要混用）</h2>
 * <ul>
 *   <li>{@code road} —— 这一段的路面列。判定「某列是不是路面」用
 *       {@link #isRoadColumn(BlockPos)}，不要拿 {@code interior} 顶替 ——
 *       洞身比路面宽，混用会把路肩当成路面。</li>
 *   <li>{@code interior} —— 洞身内可通行格（含路面，但不含墙体）；</li>
 *   <li>{@code walls} —— 洞壁与支撑格；</li>
 *   <li>{@code shell} —— 洞身 ∪ 洞壁 的去重合集，即本段要触碰的全部列；</li>
 *   <li>{@code lantern} —— 照明位置（可为 null）。</li>
 * </ul>
 */
public record PlannedSection(List<BlockPos> road,
                             List<BlockPos> interior,
                             List<BlockPos> walls,
                             List<BlockPos> shell,
                             BlockPos lantern) {

    /**
     * 该列是否属于路面。
     *
     * <p>刻意用线性查找：一个分段的 {@code road} 通常只有几十列，
     * 为它建 {@code HashSet} 的分配开销反而更大。</p>
     */
    boolean isRoadColumn(BlockPos column) {
        return column != null && road.contains(column);
    }
}