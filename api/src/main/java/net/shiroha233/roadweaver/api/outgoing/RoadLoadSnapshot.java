/* 文件职责：某一时刻聚合后的负载快照（只读），供道路侧决策使用。 */
package net.shiroha233.roadweaver.api.outgoing;

import java.util.List;

/**
 * 某一时刻全部负载信号的聚合结果，供消费方一次性读取。
 *
 * <p>由 {@link RoadLoadSignalsRegistry#snapshot()} 生成，<b>不可变</b>。</p>
 *
 * @param busy                   是否存在任何忙碌的信号源
 * @param highestBusyKind        忙碌源里优先级最高的类别；无忙碌源时为 {@code null}
 * @param recommendedWorkers     所有建议中的<b>最小值</b>；全无建议时为 {@code -1}
 * @param recommendedLanes       所有建议中的<b>最小值</b>；全无建议时为 {@code -1}
 * @param pauseRoadCoordinator   是否有源要求暂停道路协调器
 * @param pauseAuxiliaryWorldgen 是否有源要求暂停辅助 worldgen
 * @param foregroundInteraction  是否有源报告前台交互
 * @param cpuProtection          是否有源处于 CPU 保护模式
 * @param sources                参与聚合的信号源 {@code ownerId()}，诊断用
 */
public record RoadLoadSnapshot(
        boolean busy,
        LoadKind highestBusyKind,
        int recommendedWorkers,
        int recommendedLanes,
        boolean pauseRoadCoordinator,
        boolean pauseAuxiliaryWorldgen,
        boolean foregroundInteraction,
        boolean cpuProtection,
        List<String> sources) {

    /** 空快照：无任何信号源。所有调用方按「无建议」处理。 */
    public static final RoadLoadSnapshot EMPTY = new RoadLoadSnapshot(
            false, null, -1, -1, false, false, false, false, List.of());

    /** 指定类别是否忙碌。 */
    public boolean busy(LoadKind kind, List<RoadLoadSignals> all) {
        if (kind == null) {
            return false;
        }
        for (RoadLoadSignals s : all) {
            if (s != null && s.kind() == kind && s.busy()) {
                return true;
            }
        }
        return false;
    }
}
