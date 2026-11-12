package net.shiroha233.roadweaver.api.outgoing;

/**
 * 超速巡航（plaincraft-hyperspeed-lod-cruise）的<b>资源档位</b>契约。由超速飞行实现并注册，
 * 由需要按巡航档位调自己线程数的 mod（区块预生成的 chunkier 侧）查询。
 *
 * <h2>替代了什么</h2>
 *
 * <p>改造前 chunkier 侧是 {@code Class.forName("local.plaincraft.hyperspeed.CruiseState")}
 * 再按<b>方法名逐个试</b>（{@code isCpuProtectionActive} 试不到就试 {@code isActive}），
 * 取到 {@code Method} 之后每 25 ms 反射调用一次。超速飞行一改名，chunkier 侧就
 * <b>静默退回默认值</b> —— 不报错、不崩溃，只是降载不再生效。</p>
 *
 * <h2>为什么参数是「当前档位」而不是「建议值」</h2>
 *
 * <p>超速飞行只负责回答「我现在处于什么档位」，<b>具体压到几个线程由消费方决定</b>：
 * 同一个档位，Sodium 构建线程和区块预生成线程该退让的比例不一样。把策略放在提供方会让
 * 它被迫猜消费方的语义。</p>
 */
public interface CruiseSignals {

    /** 提供方 id，注册表用它去重。超速飞行固定为 {@code "hyperspeed"}。 */
    String ownerId();

    /** CPU 保护是否生效（巡航期间给其它子系统让路）。 */
    boolean cpuProtectionActive();

    /** Sodium 地形构建线程上限的建议值。消费方仍应自行夹到自己的合法区间。 */
    int sodiumBuilderThreadLimit();

    /**
     * 当前资源档位名，用于日志与诊断（例如 {@code NORMAL} / {@code PROTECTED_COMPAT}）。
     *
     * @return 档位名；不知道时返回 {@code null}，<b>不要返回空串</b>
     */
    String resourceStageName();
}
