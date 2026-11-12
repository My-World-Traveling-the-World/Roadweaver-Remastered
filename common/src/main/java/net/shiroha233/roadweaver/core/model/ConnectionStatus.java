/* 文件职责：结构连接状态枚举。 */
package net.shiroha233.roadweaver.core.model;

/**
 * 结构连接状态枚举
 */
public enum ConnectionStatus {
    PLANNED,
    GENERATING,
    COMPLETED,
    FAILED,
    /** Road exists on both sides but a verified building protection gap remains. */
    PROTECTED_GAP,
    /**
     * 规划前过滤：一端是未知端点且落在村庄 V 的结构聚合盒内，另一端恰好是 V。
     * 该连接不进规划、不进施工、不进局部修复（B 阶段）。
     */
    FILTERED
}
