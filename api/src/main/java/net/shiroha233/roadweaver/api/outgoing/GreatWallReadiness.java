package net.shiroha233.roadweaver.api.outgoing;

/**
 * 长城（plaincraft-great-wall）的<b>预热就绪查询</b>契约。由长城实现并注册，
 * 由需要「等长城预热完再动」的 mod（道路）查询。
 *
 * <h2>替代了什么</h2>
 *
 * <p>改造前道路是通过 {@code Class.forName("local.plaincraft.greatwall.lifecycle.WorldServices")}
 * 取 {@code awaitPrewarm(long, TimeUnit)} 与 {@code prewarmSnapshot()}，再用反射
 * 逐个读取快照记录里九个 accessor 拼进度签名。长城一改名、一换字段，道路就<b>静默
 * 降级</b> —— 而且这个降级的方向最坏：探针失联时如果答「就绪」，道路会直接铺在
 * 还没采样的城墙上。</p>
 *
 * <p>现在全部收敛到这个接口：方法改名是<b>编译期</b>错误，进度签名由长城自己算，
 * 道路不再需要知道快照长什么样。</p>
 *
 * <h2>失败语义（实现方必须遵守）</h2>
 *
 * <ul>
 *   <li>{@link #isReady()} 与 {@link #awaitReady(long)} <b>不得抛异常</b>；实现方内部
 *       出错应返回 {@code false}（「还没好」），不要返回 {@code true} —— 见下。</li>
 *   <li>{@link #progressSignature()} 出错返回 {@code null}（「没有信号」），
 *       <b>不要返回空串或常量</b>：那会被上位当成「有信号且一直不变」，从而误判卡死。</li>
 * </ul>
 */
public interface GreatWallReadiness {

    /** 提供方 id，注册表用它去重。长城固定为 {@code "great-wall"}。 */
    String ownerId();

    /**
     * 非阻塞询问：长城是否已经预热完成（或已失败、或本次根本不会跑）。
     *
     * @return {@code true} 表示道路可以开始了
     */
    boolean isReady();

    /**
     * 阻塞等待至多 {@code timeoutMillis}。
     *
     * @param timeoutMillis {@code <= 0} 表示只做一次非阻塞查询
     * @return {@code true} 表示已经就绪；{@code false} 表示超时仍在预热
     */
    boolean awaitReady(long timeoutMillis);

    /**
     * 进度签名：<b>只有真正有进展时才变</b>，用于上位判断「是否卡死」。
     *
     * <p><b>签名里绝不能包含时钟</b>（例如已耗时毫秒）。历史教训：第一版用
     * {@code Snapshot.toString()}，记录里带 wall-clock 时长，于是每次轮询签名都变，
     * 「五分钟内一个字段都没变」这个判据永远不成立，卡死检测<b>静默失效</b>，
     * 闸门实际只受九十分钟硬上限约束 —— 恰好就是它想防的那个故障。</p>
     *
     * <p>同理元数据字段（lane 数、总数、根候选总数等阶段常量）也不必进签名：
     * 放进去不会出错，但「只在干活时才动」的签名更容易推理。</p>
     *
     * @return 签名；{@code null} 表示本版本不提供进度信号（仅硬上限生效）
     */
    String progressSignature();
}
