/* 文件职责：我们暴露给别的 mod 的长城就绪查询：是否就绪、可等待。 */
package net.shiroha233.roadweaver.api.incoming;

import net.shiroha233.roadweaver.api.outgoing.GreatWallReadiness;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadinessRegistry;

/**
 * 查询长城预热是否就绪。消费方（道路）用这几个方法，<b>不再反射</b>。
 *
 * <p>全部方法都<b>不抛异常</b>：探针坏掉时返回「未就绪」而不是崩，也不是「就绪」。
 * 这个方向不能反 —— 答「就绪」会让道路直接铺在还没采样的城墙上，是比不设闸门更坏的
 * 故障，因为它看起来是设了闸门。</p>
 */
public final class GreatWallReadinessApi {

    private GreatWallReadinessApi() {
    }

    /** 是否有提供方注册（即长城是否在、且它这一侧接线成功）。 */
    public static boolean isLinked() {
        return GreatWallReadinessRegistry.isRegistered();
    }

    /** 非阻塞询问是否就绪。未接线或异常时返回 {@code false}。 */
    public static boolean isReady() {
        GreatWallReadiness p = GreatWallReadinessRegistry.get();
        if (p == null) return false;
        try {
            return p.isReady();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /**
     * 阻塞等待至多 {@code timeoutMillis}（{@code <= 0} 只做一次非阻塞查询）。
     *
     * @return 是否已就绪；未接线或异常时 {@code false}
     */
    public static boolean awaitReady(long timeoutMillis) {
        GreatWallReadiness p = GreatWallReadinessRegistry.get();
        if (p == null) return false;
        try {
            return timeoutMillis <= 0 ? p.isReady() : p.awaitReady(timeoutMillis);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** 只采信明确的终态失败，探针缺失或异常不代表长城已经结案。 */
    public static boolean isTerminalFailure() {
        GreatWallReadiness p = GreatWallReadinessRegistry.get();
        if (p == null) return false;
        try {
            return p.isTerminalFailure();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** 进度签名；未接线、无信号或异常时 {@code null}。 */
    public static String progressSignature() {
        GreatWallReadiness p = GreatWallReadinessRegistry.get();
        if (p == null) return null;
        try {
            return p.progressSignature();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** 一行诊断：注册了谁、当前就绪否、进度签名。 */
    public static String describe() {
        GreatWallReadiness p = GreatWallReadinessRegistry.get();
        if (p == null) {
            return "greatwall-readiness: linked=false";
        }
        String sig;
        try {
            sig = p.progressSignature();
        } catch (RuntimeException | LinkageError failure) {
            sig = "<failed: " + failure + ">";
        }
        return "greatwall-readiness: linked=true provider=" + p.ownerId()
                + " ready=" + isReady() + " signature=" + sig;
    }
}
