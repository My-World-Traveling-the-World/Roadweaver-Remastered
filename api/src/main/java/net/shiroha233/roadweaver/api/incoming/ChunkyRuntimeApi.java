package net.shiroha233.roadweaver.api.incoming;

import net.shiroha233.roadweaver.api.outgoing.ChunkyRuntime;
import net.shiroha233.roadweaver.api.outgoing.ChunkyRuntimeRegistry;

/**
 * 查询 chunky-autogen 的运行期状态。
 *
 * <p><b>任何情况下都不抛异常。</b>没有提供方时返回 {@code false} —— 对两个消费方来说
 * 这正是它们要的降级语义：没有 chunky 就没有预生成在跑，也就没有配置需要热重载。</p>
 */
public final class ChunkyRuntimeApi {

    private ChunkyRuntimeApi() {
    }

    /** chunky-autogen 是否在、且注册成功。 */
    public static boolean isLinked() {
        return ChunkyRuntimeRegistry.isRegistered();
    }

    /**
     * 预生成是否有任务在跑。
     *
     * <p>没有提供方时返回 {@code false}：调用方（时间冻结）据此保持空闲。</p>
     */
    public static boolean hasActiveTasks() {
        ChunkyRuntime runtime = ChunkyRuntimeRegistry.get();
        if (runtime == null) return false;
        try {
            return runtime.hasActiveTasks();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /**
     * 让 chunky 重新读取配置文件。
     *
     * @return {@code true} 表示确有提供方执行了重载；{@code false} 表示没有提供方（正常降级）
     */
    public static boolean reloadSettings() {
        ChunkyRuntime runtime = ChunkyRuntimeRegistry.get();
        if (runtime == null) return false;
        try {
            return runtime.reloadSettings();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** 一行诊断。 */
    public static String describe() {
        ChunkyRuntime runtime = ChunkyRuntimeRegistry.get();
        if (runtime == null) return "chunky-runtime: linked=false";
        return "chunky-runtime: linked=true provider=" + runtime.ownerId()
                + " active=" + hasActiveTasks();
    }
}
