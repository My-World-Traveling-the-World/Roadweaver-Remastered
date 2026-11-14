/* 文件职责：外部负载的类型标签（预生成/传送/巡航/前台/其它）——决定道路要不要让路。 */
package net.shiroha233.roadweaver.api.outgoing;

/**
 * 负载信号的类别。
 *
 * <p>消费方（RoadWeaver、c2me QoS）对<b>不同类别用不同的处理策略</b>，
 * 所以类别必须是枚举而不是让调用方去猜 {@code ownerId()} 字符串：</p>
 * <ul>
 *   <li>传送类（{@link #TELEPORT}）优先级最高 —— 玩家正在跨维度，卡住体验最差；</li>
 *   <li>预生成类（{@link #PREGENERATION}）独占期可以吃满 CPU；</li>
 *   <li>巡航类（{@link #CRUISE}）是持续性的，需要把别人的线程压低而不是暂停。</li>
 * </ul>
 */
public enum LoadKind {

    /** 区块预生成（Chunky 预热 / 正式运行）。 */
    PREGENERATION(3),

    /** 传送类预热（黄昏传送、传送门稳定）。优先级最高。 */
    TELEPORT(4),

    /** 高速移动 / 巡航（超速飞行）。 */
    CRUISE(2),

    /** 玩家前台交互（会抢占客户端与服务端预算）。 */
    FOREGROUND(1),

    /** 其它。 */
    OTHER(0);

    private final int priority;

    LoadKind(int priority) {
        this.priority = priority;
    }

    /** 数值越大优先级越高。冲突时消费方按这个裁决。 */
    public int priority() {
        return priority;
    }
}
