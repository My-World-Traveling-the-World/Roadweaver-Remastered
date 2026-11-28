/* 文件职责：保留纵断面拒绝的站点和互斥高度，开局重试不能把它压成无原因的失败。 */
package net.shiroha233.roadweaver.core.model;

import net.minecraft.core.BlockPos;

/** 只存诊断数值，不保留整条地形数组或世界对象。 */
public record ProfileConflict(Reason reason, int station, int limitingStation,
                              int requiredMinY, int reachableMaxY, BlockPos repairCenter) {
    public ProfileConflict(Reason reason, int station, int limitingStation, int requiredMinY, int reachableMaxY) {
        this(reason, station, limitingStation, requiredMinY, reachableMaxY, null);
    }

    /** 把正式站序坐标交给下一轮；粗采节点下标和正式站号不能互用。 */
    public ProfileConflict atRepairCenter(BlockPos center) {
        return new ProfileConflict(reason, station, limitingStation, requiredMinY, reachableMaxY, center);
    }

    /** 原因是实际触发的判据；后续重试仍须验证整个施工产物。 */
    public enum Reason { LOCKED_HEIGHT, COVER, CUT_BUDGET, SLOPE_WINDOW, NO_FEASIBLE_PROFILE }

    /** 服务端逐路诊断和最终汇总传递同一个见证，堆栈仍在生成入口记录。 */
    public static final class Rejected extends IllegalArgumentException {
        private final ProfileConflict conflict;

        public Rejected(ProfileConflict conflict) {
            super("纵断面在桥面锁高、已选隧道和施工预算下仍无解，拒绝发布：" + conflict);
            this.conflict = conflict;
        }

        public ProfileConflict conflict() {
            return conflict;
        }
    }
}
