/* 文件职责：隧道建造的输出数据类型 —— 从 RoadTunnelBuilder 抽出以控制单文件规模。 */
package net.shiroha233.roadweaver.pathlogic.tunnel;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;

import static net.shiroha233.roadweaver.pathlogic.tunnel.PlannedSectionBuilder.unique;

/**
 * 隧道单段的建造计划（2026-11-15 从 {@link RoadTunnelBuilder} 抽出）。
 *
 * <p><b>为什么搬到独立文件：</b>{@code RoadTunnelBuilder} 已到 1002 行，
 * 超出「单文件不超过 1000 行」的工程约束。本 record 及本文件末尾的三个包内类型
 * 都是<b>纯数据定义</b>，与建造流程的职责不同，是最自然的分割点。</p>
 *
 * <p><b>为什么外部引用不需要改：</b>它们与 {@code RoadTunnelBuilder} 同属
 * {@code pathlogic.tunnel} 包，Java 同包引用免 {@code import}。
 * {@code PlannedSection} 的 3 处跨包引用需要各自补 {@code import}，
 * 由本次改动的另一半处理。</p>
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@code interior} —— 洞身内可通行格（不含墙体）；</li>
 *   <li>{@code walls} —— 洞壁装饰/支撑格；</li>
 *   <li>{@code lantern} + {@code placeLantern} —— 照明位置与是否放置；</li>
 *   <li>{@code road} —— 所属道路数据（用于取宽度、路面材料等）；</li>
 *   <li>{@code portal} / {@code junction} / {@code scenic} —— 洞口、路网接驳、观景三种形态标记；</li>
 *   <li>{@code blocked} / {@code protectedGap} —— 两种「本段不挖」的情形：
 *       前者地形/结构不允许，后者是留给受保护区域或既有建筑的缺口。
 *       它们与「空洞身」的区别在于<b>原因不同</b>，日志与台账要能分开统计。</li>
 * </ul>
 */
public record TunnelPlan(List<BlockPos> interior, List<BlockPos> walls, BlockPos lantern, boolean placeLantern,
                         RoadData road, boolean portal, boolean junction, boolean scenic, boolean blocked,
                         boolean protectedGap) {

    /** 空计划：本段既不是隧道也不需要特殊处理。 */
    static final TunnelPlan NONE =
            new TunnelPlan(List.of(), List.of(), null, false, null, false, false, false, false, false);

    /** 被阻挡：地形或结构不允许开挖，本段不产生隧道。 */
    static final TunnelPlan BLOCKED =
            new TunnelPlan(List.of(), List.of(), null, false, null, false, false, false, true, false);

    /** 受保护缺口：为受保护区域/既有建筑刻意留空。 */
    static final TunnelPlan PROTECTED_GAP =
            new TunnelPlan(List.of(), List.of(), null, false, null, false, false, false, true, true);

    /** 紧凑构造器：两个列表做不可变拷贝，避免调用方后续修改影响已产出的计划。 */
    public TunnelPlan {
        interior = List.copyOf(interior);
        walls = List.copyOf(walls);
    }

    public static TunnelPlan none() {
        return NONE;
    }

    public static TunnelPlan blockedPlan() {
        return BLOCKED;
    }

    public static TunnelPlan protectedGapPlan() {
        return PROTECTED_GAP;
    }

    /** 是否真的挖出了洞身。 */
    public boolean active() {
        return !interior.isEmpty();
    }

    /**
     * 洞身 + 洞壁的去重合集 —— 即「这一段要处理到的全部方块列」。
     *
     * <p>用 {@link PlannedSectionBuilder#unique} 按 XZ 列去重：
     * 同一个 XZ 列在洞身和洞壁里都出现时只保留一个，
     * 否则后续清空/放置会重复处理同一列。</p>
     */
    public List<BlockPos> shell() {
        Map<Long, BlockPos> all = unique(interior);
        all.putAll(unique(walls));
        return List.copyOf(all.values());
    }
}

// ── 以下三个是隧道内部辅助类型，与 TunnelPlan 同属「建造流程的纯数据」，
//    一起放在本文件（Java 允许一个文件内一个 public 顶层类型 + 任意多个包内类型）。

/**
 * 安全判定结果。
 *
 * <p>把「是否安全 + 原因 + 出问题的位置」打包返回，比只返回 boolean 好排查：
 * 日志里能直接说清「为什么这一段不挖」，而不是只看到一个 false。</p>
 */
record SafetyCheck(boolean safe, String reason, BlockPos position) {

    /** 安全：可以正常开挖。 */
    static SafetyCheck success() {
        return new SafetyCheck(true, "ready", null);
    }

    /** 不安全：带上原因与位置，便于日志与台账定位。 */
    static SafetyCheck failure(String reason, BlockPos position) {
        return new SafetyCheck(false, reason, position);
    }
}

/**
 * 分段区间（按站索引的半开区间 {@code [start, end)}）。
 *
 * <p>刻意存索引而不是坐标：跨度判定、长度统计都要回到中心线列表上取数，
 * 存索引可以避免「坐标反查站号」这类容易错一位的操作。</p>
 */
record Span(int start, int end) {

    /**
     * 该区间沿中心线的实际弧长（取整）。
     *
     * <p>逐段累加 XZ 平面欧氏距离 —— 不按站数乘固定步长，
     * 因为中心线站距并不均匀，那样会高估或低估长度。</p>
     */
    int length(List<BlockPos> centers) {
        double total = 0;
        for (int i = start; i < end; i++) {
            total += Math.hypot(centers.get(i + 1).getX() - centers.get(i).getX(),
                    centers.get(i + 1).getZ() - centers.get(i).getZ());
        }
        return (int) Math.round(total);
    }
}

/**
 * 跨段状态键：用首尾站坐标 + 道路两端坐标标识一个「跨」。
 *
 * <p>四个 long 一起才能区分「哪条路的哪一段」——
 * 只用首尾坐标会在多条路共用端点时冲突。</p>
 */
record SpanKey(long first, long second, long ownerFirst, long ownerSecond) {
}
