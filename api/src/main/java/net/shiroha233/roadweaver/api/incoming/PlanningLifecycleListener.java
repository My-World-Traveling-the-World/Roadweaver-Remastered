package net.shiroha233.roadweaver.api.incoming;

/**
 * 初始生成的阶段回调。
 *
 * <h2>用来替代 mixin</h2>
 * <p>great-wall 和 streams 目前都往 RoadWeaver 的<b>内部实现类</b>
 * {@code generation.InitialGenManager} 上打 {@code @Pseudo @Mixin}：</p>
 * <pre>
 * great-wall  @Inject(method = "prewarmInitialRiverPlates", require = 1)  // 硬依赖：方法没了就类加载崩
 * streams     @Inject(method = "blockUntilDone",          require = 0)  // 软依赖：方法没了静默跳过
 * </pre>
 * <p>改成回调后，RoadWeaver 内部方法改名只影响自己，不再有任何外部 mod 会因为
 * 一个私有方法改名而崩溃或静默失效。</p>
 *
 * <p>所有回调都在<b>服务端线程</b>触发。回调内抛出的异常会被 RoadWeaver 捕获并记日志，
 * 不会中断道路生成 —— 但不要在回调里做长耗时操作。</p>
 */
public interface PlanningLifecycleListener {

    /**
     * 河流预热完成。<b>已废弃 —— 请改用 {@link RiverReadinessApi}。</b>
     *
     * <p>这个回调由道路在<b>结构扫描之后</b>的河流步骤里触发，实测<b>时机太晚</b>：
     * 长城要等的是「河流就绪」，而那个时刻只有河流 mod 自己知道，不是道路。
     * 真机时间线显示长城在本回调触发之前就已经自己预热完了，事件被幂等吞掉；
     * 当时真正起作用的反而是河流 mod 里一条硬编码反射直连。</p>
     *
     * <p>「河流就绪」的正确通道是 {@link RiverReadinessApi}：河流提供方广播、
     * 订阅方注册，注册表在 api 模块内，<b>不经过道路后端</b>，因此也不受
     * 「道路后端尚未注入」的影响。</p>
     *
     * <p>本方法仍会被触发（保留给尚未迁移的消费者），但新代码不要再订阅它。</p>
     *
     * @deprecated 用 {@link RiverReadinessApi#addListener} 替代
     */
    @Deprecated
    default void onRiversPrewarmed() {
    }

    /** 初始生成开始。streams 用它进入采样加强档。 */
    default void onInitialGenerationStart() {
    }

    /** 初始生成结束（无论成败）。streams 用它退出采样加强档。 */
    default void onInitialGenerationEnd(InitialGenerationSummary summary) {
    }

    /**
     * 初始生成结果摘要。
     *
     * @param roadsRequested 请求生成的连线数
     * @param roadsBuilt     实际建成的道路数
     * @param roadsFailed    失败的连线数
     * @param elapsedMillis  本阶段墙钟耗时（毫秒）
     */
    record InitialGenerationSummary(int roadsRequested, int roadsBuilt,
                                    int roadsFailed, long elapsedMillis) {
    }
}
