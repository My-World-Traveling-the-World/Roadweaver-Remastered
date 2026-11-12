package net.shiroha233.roadweaver.api.incoming;

import net.shiroha233.roadweaver.api.outgoing.CruiseSignals;
import net.shiroha233.roadweaver.api.outgoing.CruiseSignalsRegistry;

/**
 * 查询超速巡航的资源档位。消费方（区块预生成侧）用这几个方法，<b>不再反射</b>。
 *
 * <p>全部方法<b>不抛异常</b>，未接线时返回「没意见」的保守值：{@code false} / {@code -1} / {@code null}。
 * 消费方据此退回自己的默认值 —— <b>别把 {@code -1} 当成「零线程」</b>，它是「提供方没意见」。</p>
 */
public final class CruiseSignalsApi {

    /** 提供方未给出建议时的哨兵值。 */
    public static final int NO_OPINION = -1;

    private CruiseSignalsApi() {
    }

    /** 是否有提供方注册（即超速飞行是否在、且它那一侧接线成功）。 */
    public static boolean isLinked() {
        return CruiseSignalsRegistry.isRegistered();
    }

    /** CPU 保护是否生效；未接线或异常返回 {@code false}。 */
    public static boolean cpuProtectionActive() {
        CruiseSignals p = CruiseSignalsRegistry.get();
        if (p == null) return false;
        try {
            return p.cpuProtectionActive();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Sodium 构建线程上限建议值；未接线或异常返回 {@link #NO_OPINION}。 */
    public static int sodiumBuilderThreadLimit() {
        CruiseSignals p = CruiseSignalsRegistry.get();
        if (p == null) return NO_OPINION;
        try {
            return p.sodiumBuilderThreadLimit();
        } catch (RuntimeException | LinkageError failure) {
            return NO_OPINION;
        }
    }

    /** 资源档位名；未接线、未知或异常返回 {@code null}。 */
    public static String resourceStageName() {
        CruiseSignals p = CruiseSignalsRegistry.get();
        if (p == null) return null;
        try {
            return p.resourceStageName();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** 一行诊断。 */
    public static String describe() {
        CruiseSignals p = CruiseSignalsRegistry.get();
        if (p == null) return "cruise-signals: linked=false";
        return "cruise-signals: linked=true provider=" + p.ownerId()
                + " cpuProtection=" + cpuProtectionActive()
                + " sodiumLimit=" + sodiumBuilderThreadLimit()
                + " stage=" + resourceStageName();
    }
}
