package net.shiroha233.roadweaver.api.incoming;
/**
 * 规划区域的就绪状态。
 *
 * <p>取代原先由 {@code RoadPlanningReadinessIndex} 返回的裸 {@code String} ——
 * 字符串拼错编译期不报错，是「联动静默失效」的典型来源。</p>
 */
public enum ReadinessState {

    /** 已就绪，协调器可以开始该区域。 */
    READY,

    /** 尚未轮到该区域。 */
    NOT_STARTED,

    /** 正在规划中。 */
    IN_PROGRESS,

    /** 被前置条件挡住（如长城预热未完成、Chunky 独占期）。 */
    BLOCKED,

    /** RoadWeaver 不可用或状态未知 —— 调用方应按「不阻塞」处理。 */
    UNKNOWN;

    /** 是否可以安全开始。 */
    public boolean canStart() {
        return this == READY;
    }

    /** 是否应当继续排队等待。 */
    public boolean shouldWait() {
        return this == IN_PROGRESS || this == BLOCKED;
    }
}
