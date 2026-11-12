/* 文件职责：规划快照不可用 —— 调用方必须显式处理「没有快照」这条路径。 */
package net.shiroha233.roadweaver.pathlogic.structures;


public final class PlanningSnapshotUnavailableException extends RuntimeException {
    public PlanningSnapshotUnavailableException(String message) {
        super(message);
    }
}
