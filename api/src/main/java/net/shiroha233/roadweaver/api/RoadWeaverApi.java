package net.shiroha233.roadweaver.api;

import java.util.Collections;
import java.util.Set;

/**
 * RoadWeaver 对外 API 的总入口。
 *
 * <h2>这个模块是什么</h2>
 * <p>{@code roadweaver-api} 是一个<b>独立部署</b>的小 mod（mod id {@code roadweaver_api}），
 * 只包含契约（接口、枚举、常量、注册表），<b>不包含任何实现</b>。</p>
 *
 * <h2>为什么契约必须与实现物理分离</h2>
 * <p>历史上 {@code net.shiroha233.roadweaver.api.interop} 就是契约与实现放在一起，
 * RoadWeaver 0.8.406 删掉实现时把契约一起删了，外部五个 mod 全部静默失效；
 * 更糟的是它们的测试用的是同一份 compile-only 幽灵 jar，所以测试一直绿、运行时才炸。
 * 原话见 {@code plaincraft-great-wall/build.gradle}：
 * <i>"a compile-only jar that no longer matches any deployed peer is a trap, not a dependency"</i>.</p>
 *
 * <p>现在的三条隔离：</p>
 * <ol>
 *   <li>本模块不 import {@code net.shiroha233.roadweaver} 下任何非 api 包
 *       （由 RoadWeaver 侧的 {@code ApiModulePurityTest} 钉住）；</li>
 *   <li>实现通过 {@link RoadWeaverBackend} 注入，本模块对实现零引用 ——
 *       所以 RoadWeaver 不装时本模块仍能正常加载；</li>
 *   <li>消费方在 {@code fabric.mod.json} 里 {@code depends: { "roadweaver_api": ">=1" }}，
 *       缺失是<b>加载期的明确报错</b>，不是运行期的 {@code NoClassDefFoundError}。</li>
 * </ol>
 *
 * <h2>两个方向</h2>
 * <ul>
 *   <li>{@code api.incoming} —— <b>别人调用我们</b>：结构预留、规划生命周期、规划就绪。</li>
 *   <li>{@code api.outgoing} —— <b>我们和别人协调</b>：别人实现 {@link net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals}
 *       告诉我们「该降多少线程」，我们通过 {@link net.shiroha233.roadweaver.api.outgoing.StreamsThreadControl}
 *       让 streams 调整采样线程。</li>
 * </ul>
 */
public final class RoadWeaverApi {

    /**
     * API 主版本。只有<b>破坏性改动</b>（删方法、改签名、改语义）才递增。
     * 新增能力用 {@link #hasCapability} 判断，不动这个数。
     */
    public static final int API_VERSION = 1;

    /** 能力：结构占地预留。 */
    public static final String CAP_STRUCTURE_RESERVATION = "structure.reservation";
    /** 能力：规划生命周期回调。 */
    public static final String CAP_PLANNING_LIFECYCLE = "planning.lifecycle";
    /** 能力：规划就绪查询与协调。 */
    public static final String CAP_PLANNING_READINESS = "planning.readiness";
    /** 能力：跨 mod 运行时负载信号。 */
    public static final String CAP_LOAD_SIGNALS = "load.signals";

    private static volatile RoadWeaverBackend backend;

    private RoadWeaverApi() {
    }

    /**
     * 由 RoadWeaver 实现在初始化时调用，注入后端。
     *
     * <p><b>外部 mod 不要调用这个方法。</b>它是给实现方用的。</p>
     *
     * @param instance 后端实现，传 {@code null} 表示卸载
     */
    public static void installBackend(RoadWeaverBackend instance) {
        backend = instance;
        if (instance != null) {
            // 把「后端未就绪时先排队」的 lifecycle 监听器补注册进来。
            // 存在这个队列的原因：Fabric 的 depends 只保证契约 mod 先加载，
            // **不保证本 mod 的 onInitialize 早于别人的** —— 实测长城比我早 3 秒，
            // 旧实现下它的回调会静默丢失。
            net.shiroha233.roadweaver.api.incoming.PlanningLifecycleApi.flushPending(instance);
        }
    }

    /** 当前注入的后端，可能为 {@code null}。内部用。 */
    public static RoadWeaverBackend backend() {
        return backend;
    }

    /**
     * RoadWeaver 是否可用。
     *
     * <p>外部 mod 用它做软依赖判断：返回 {@code false} 时应当跳过全部 RoadWeaver 联动，
     * 而不是继续调用然后捕获异常。</p>
     */
    public static boolean isAvailable() {
        RoadWeaverBackend b = backend;
        return b != null && b.isAvailable();
    }

    /** 当前后端声明的能力集合；无后端时为空集。 */
    public static Set<String> capabilities() {
        RoadWeaverBackend b = backend;
        if (b == null) {
            return Collections.emptySet();
        }
        Set<String> caps = b.capabilities();
        return caps == null ? Collections.emptySet() : Collections.unmodifiableSet(caps);
    }

    /** 查询某个能力是否可用（用 {@code CAP_*} 常量）。 */
    public static boolean hasCapability(String capability) {
        return capability != null && capabilities().contains(capability);
    }

    /**
     * 一行诊断文本，用于日志排查「联动到底有没有生效」。
     *
     * <p>形如：{@code api=1 available=true caps=[structure.reservation, planning.lifecycle]}。
     * 目前联动失效最难排查的就是「以为是没装，其实是名字对不上」，这行能直接区分。</p>
     */
    public static String describe() {
        RoadWeaverBackend b = backend;
        if (b == null) {
            return "api=" + API_VERSION + " available=false backend=absent";
        }
        return "api=" + API_VERSION + " available=" + b.isAvailable() + " caps=" + capabilities();
    }
}
