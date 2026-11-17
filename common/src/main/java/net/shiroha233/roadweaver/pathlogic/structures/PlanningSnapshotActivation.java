/* 文件职责：隔离当前线程快照的临时扩宽与嵌套恢复，不决定快照是否可用。 */
package net.shiroha233.roadweaver.pathlogic.structures;

final class PlanningSnapshotActivation {
    private PlanningSnapshotActivation() {}
    static StructureAvoidanceService.PlanningScope activate(PlanningSnapshot selected, int additionalRoadRadius) {
        // 恢复原对象而非重建，确保嵌套搜索退出后仍使用外层的保护口径。
        PlanningSnapshot previous = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();

        if (additionalRoadRadius > 0) {
            selected = selected.withAdditionalRoadRadius(additionalRoadRadius);
        }
        StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(selected);
        return () -> {
            if (previous == null) StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
            else StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(previous);
        };
    }
}
