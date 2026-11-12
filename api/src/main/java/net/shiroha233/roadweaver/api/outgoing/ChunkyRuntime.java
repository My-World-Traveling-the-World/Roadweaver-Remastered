package net.shiroha233.roadweaver.api.outgoing;

/**
 * chunky-autogen 的<b>运行期状态</b>契约：别的 mod 需要知道「预生成在不在跑」和
 * 「改完你的配置，你能不能立刻重读」。
 *
 * <h2>为什么合成一个契约</h2>
 *
 * <p>原先有两个互不相干的 mod 各自 {@code Class.forName} 反射进 chunky：</p>
 * <ul>
 *   <li>{@code plaincraft-autotune} —— 改完 chunky 的配置文件后想让它立刻生效，
 *       反射调 {@code StreamingSettings.reload()}；</li>
 *   <li>{@code plaincraft-chunky-time-freeze} —— 想在预生成期间冻结游戏内时间，
 *       反射调 {@code AdaptiveChunkController.hasActiveTasks()}。</li>
 * </ul>
 *
 * <p>两个目标类都在 chunky 里，所以<b>由 chunky 提供一个契约、两个消费方各取所需</b>，
 * 比开两份契约更省。方法名一改，两个消费方都会在<b>编译期</b>报错，不再静默失效。</p>
 *
 * <h2>失败语义</h2>
 *
 * <p>两个消费方的降级都是良性的：{@link #hasActiveTasks()} 返回 {@code false} 时
 * 时间冻结保持空闲（白天黑夜照常走）；{@link #reloadSettings()} 返回 {@code false} 时
 * autotune 只是不做热重载，配置下次启动照样读得到。</p>
 */
public interface ChunkyRuntime {

    /** 提供方 id，注册表用它去重。chunky-autogen 固定为 {@code "chunky-autogen"}。 */
    String ownerId();

    /** 预生成当前是否有任务在跑。 */
    boolean hasActiveTasks();

    /**
     * 让 chunky 重新读取自己的配置文件。
     *
     * @return 是否真的执行了重载
     */
    boolean reloadSettings();
}
