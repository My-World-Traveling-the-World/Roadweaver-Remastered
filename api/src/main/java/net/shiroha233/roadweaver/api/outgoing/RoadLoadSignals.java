/* 文件职责：别的 mod 提供给我们的负载信号契约：是否忙、是否该暂停道路协调、推荐线程数/规划道数。 */
package net.shiroha233.roadweaver.api.outgoing;

/**
 * 运行时负载信号：<b>由别的 mod 实现并注册</b>，RoadWeaver 与 c2me QoS 据此决定自己的线程数。
 *
 * <h2>为什么必须是接口，而不是继续用反射</h2>
 * <p>现在 RoadWeaver 用硬编码字符串反射探测这些信息：</p>
 * <pre>
 * "local.plaincraftchunkyautogen.AdaptiveChunkController", "recommendedRoadWorkerThreads"
 * "local.plaincraft.hyperspeed.CruiseState",               "isCpuProtectionActive"
 * "local.plaincraftchunkyautogen.PlayerChunkPriorityGuard", "isInteractionActive"
 * </pre>
 * <p>被探测方<b>完全不知道自己被依赖</b> —— 它改个方法名，RoadWeaver 就静默退回默认线程数，
 * 表现为「超速飞行时道路还是很卡」这种无法排查的现象。</p>
 *
 * <p>改成接口后，提供方<b>在自己的仓库里编译期就会报错</b>：少实现一个方法、
 * 改一个方法名，都过不了 {@code compileJava}。</p>
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>实现方在自己 mod 的初始化里调
 *       {@link RoadLoadSignalsRegistry#register(RoadLoadSignals)}；</li>
 *   <li>所有方法都会被<b>任意线程</b>调用，实现必须线程安全且<b>绝不允许阻塞</b>
 *       （不要在实现里加锁等待、不要读磁盘）；</li>
 *   <li>返回值 {@code -1} 表示「无建议」，消费方使用自己算的值；</li>
 *   <li>不要在这里做耗时计算 —— 消费方可能每秒调用多次。</li>
 * </ul>
 */
public interface RoadLoadSignals {

    /** 唯一标识，如 {@code "chunky-autogen"} / {@code "hyperspeed"}。诊断日志与去重都用它。 */
    String ownerId();

    /** 信号类别，决定消费方的处理策略。 */
    LoadKind kind();

    /** 当前是否处在会抢 CPU 的阶段。 */
    boolean busy();

    /** 是否要求 RoadWeaver 暂停规划协调器。默认 {@code false}。 */
    default boolean shouldPauseRoadCoordinator() {
        return false;
    }

    /**
     * 建议给 RoadWeaver 的 worker 线程数。
     *
     * <p><b>为什么要带参数</b>：提供方的推荐值本来就是自适应的
     * （chunky-autogen 的 {@code recommendedRoadWorkerThreads(int)} 会参考当前线程数），
     * 如果接口做成无参，提供方就只能瞎猜一个值传进去，推荐反而失真。
     * 这里把调用方的现状透传下去，语义与改造前的反射调用完全一致。</p>
     *
     * @param currentThreads 道路当前使用的 worker 线程数
     * @return {@code -1} 表示无建议（道路用自己算的值）
     */
    default int recommendedRoadWorkerThreads(int currentThreads) {
        return -1;
    }

    /**
     * 建议给 RoadWeaver 的规划 lane 数。
     *
     * @param pendingRegions  尚未开始规划的已排队区域数
     * @param readyAheadRegions 提前就绪的区域数
     * @param currentLanes    道路当前使用的 lane 数
     * @return {@code -1} 表示无建议
     */
    default int recommendedRoadPlanningLanes(int pendingRegions, int readyAheadRegions, int currentLanes) {
        return -1;
    }

    /** 消费者 id：RoadWeaver 自己的规划线程。 */
    String CONSUMER_ROAD = "road";
    /** 消费者 id：Streams 的采样线程。 */
    String CONSUMER_STREAMS = "streams";

    /**
     * 建议给<b>指定消费者</b>的 worker 线程数。
     *
     * <p><b>为什么需要它</b>：提供方的推荐值本来就是按消费者区分的 ——
     * chunky-autogen 有 {@code recommendedRoadWorkerThreads(int)} 和
     * {@code recommendedStreamsWorkerThreads(int)} 两个独立算法。
     * 只做「道路专用」的接口会逼着 Windows 之外的消费者继续走反射。</p>
     *
     * @param consumerId    消费者标识，用 {@link #CONSUMER_ROAD} / {@link #CONSUMER_STREAMS}
     * @param currentThreads 该消费者当前使用的线程数
     * @return {@code -1} 表示无建议
     */
    default int recommendedThreadsFor(String consumerId, int currentThreads) {
        return CONSUMER_ROAD.equals(consumerId) ? recommendedRoadWorkerThreads(currentThreads) : -1;
    }

    /** 是否要求暂停辅助 worldgen（非道路的补充生成）。默认 {@code false}。 */
    default boolean shouldPauseAuxiliaryWorldgen() {
        return false;
    }

    /**
     * 玩家是否正在进行<b>前台交互</b>（点击方块 / 使用物品 / 打开界面等）。
     * 默认 {@code false}。
     */
    default boolean foregroundInteraction() {
        return false;
    }

    /**
     * 玩家是否正在进行<b>任何前台活动</b>（移动 <b>或</b> 交互）。
     * 默认退化为 {@link #foregroundInteraction()}。
     *
     * <p><b>为什么不能和 {@link #foregroundInteraction()} 合并</b>：这两个在
     * chunky-autogen 里是<b>两个不同的判定</b> ——
     * {@code isInteractionActive()} 只看交互，而
     * {@code isForegroundActivityActive()} 是「{@code movingPlayers > 0 || 交互}」。
     * 改造前 RoadWeaver 读前者、c2meQoS 读后者。若在这里合并成一个，
     * 两个消费方的行为会同时改变，而且是静默的。</p>
     */
    default boolean foregroundActivity() {
        return foregroundInteraction();
    }

    /** CPU 保护模式（如巡航飞行时会持续压低其它 mod 的线程）。默认 {@code false}。 */
    default boolean cpuProtection() {
        return false;
    }
}
