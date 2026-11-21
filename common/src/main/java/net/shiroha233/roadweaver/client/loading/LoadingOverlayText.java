/* 文件职责：加载界面的纯展示逻辑（文本与数值派生）—— 不画任何东西、不碰 GuiGraphics。 */
package net.shiroha233.roadweaver.client.loading;

import java.util.Locale;

import net.minecraft.network.chat.Component;

import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressSnapshot.RoutePhaseProgress;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;

/**
 * 加载覆盖层的展示逻辑。
 *
 * <h2>为什么要把这些从渲染器里拆出来</h2>
 * <p>原来的 {@code LoadingGenerationOverlayRenderer} 有 585 行、28 个方法，
 * 里面混着两类完全不同的东西：</p>
 * <ul>
 *   <li><b>画</b> —— 画路肩、画树、画手推车、画勾选、画脉冲环</li>
 *   <li><b>算</b> —— 剩余时间怎么换算、时长怎么格式化、操作名怎么翻成人话、
 *       状态优先级怎么判、设备名回退到后端名</li>
 * </ul>
 *
 * <p>混在一起的代价是第二类<b>完全无法测试</b>：要测「进度 30% 时 ETA 显示对不对」，
 * 得先造一个 Minecraft 客户端。而这一类恰恰是最容易算错的部分
 * （浮点速率、向上取整、分钟进位、状态字符串的优先级）。</p>
 *
 * <h2>拆分口径</h2>
 * <p>本类的所有方法都只收<b>普通数据</b>（long / double / String / 小记录），
 * 既不收 {@code InitialGenerationProgressSnapshot}（45 个字段，造一个很费劲），
 * 也不收 {@code GuiGraphics}。调用方明确写出「显示依赖哪几个字段」。</p>
 *
 * <p>形如颜色、坐标、字体这类只属于绘制的东西一律留在渲染器里。</p>
 *
 * @see LoadingGenerationOverlayRenderer
 */
public final class LoadingOverlayText {

    private LoadingOverlayText() {}

    /**
     * 把毫秒格式化成人看的时长。
     *
     * <p>约定：不足 60 秒写成 {@code 12s}，超过写成 {@code 1:05}。
     * 负数按 0 处理 —— 时钟回拨或计时器未初始化都可能产出负值，
     * 显示 {@code -1s} 会让玩家以为卡住了。</p>
     */
    public static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        if (seconds >= 60L) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
        }
        return seconds + "s";
    }

    /**
     * 精确采样的剩余时间估算。
     *
     * <p>速率未知（≤0）时返回 {@code --} 而不是 0 —— 早期阶段速率为 0
     * 会让玩家看到「剩余 0 秒」然后一直等下去。</p>
     */
    public static String exactSamplingEta(double samplesPerSecond, long samplesTotal, long samplesDone) {
        if (samplesPerSecond <= 0.0) {
            return "--";
        }
        long remaining = Math.max(0L, samplesTotal - samplesDone);
        long seconds = (long) Math.ceil(remaining / samplesPerSecond);
        return formatDuration(seconds * 1000L);
    }

    /** 最近一批采样的速率（样本/秒）。没有批次数据时返回 {@code --}。 */
    public static String exactBatchRate(int lastBatchSamples, long lastBatchMillis) {
        if (lastBatchMillis <= 0L) {
            return "--";
        }
        return String.format(Locale.ROOT, "%.0f", lastBatchSamples * 1000.0 / lastBatchMillis);
    }

    /** 空值统一显示成 {@code -}，避免界面上出现 null。 */
    public static String printable(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * 设备名，回退到后端名。
     *
     * <p>OpenCL 可用但没枚举出设备名时，只显示后端（CPU / GPU）也比显示一个
     * 短横线有用 —— 玩家至少知道当前走的是哪条后端。</p>
     */
    public static String printableDevice(String deviceName, String backend) {
        String name = printable(deviceName);
        if (!"-".equals(name)) {
            return name;
        }
        return printable(backend);
    }

    /**
     * 把内部操作标识翻成玩家能看懂的状态。
     *
     * <h2>判定顺序是有意义的，不能重排</h2>
     * <p>{@code sampling_planning} 同时含有 sampling 与 planning，必须<b>先</b>
     * 判「两者都有」再判单个，否则永远只显示「采样中」。同理，后面的
     * {@code handed_to_candidate} / {@code retained_protected_gap} 这类长标识
     * 必须先于通用的 {@code candidate} / {@code complete} 判定。</p>
     */
    public static String statusLabel(String operation) {
        if (operation == null || operation.isBlank() || "idle".equals(operation)) return "-";
        String value = operation.toLowerCase(Locale.ROOT);
        if (value.contains("sampling") && value.contains("planning")) return "采样/规划中";
        if (value.contains("sampling") || value.contains("sample")) return "采样中";
        if (value.contains("planning") || value.contains("plan")) return "规划中";
        if (value.contains("structure") || value.contains("corridor") || value.contains("obstacle")
                || value.contains("discovering")) return "规划中";
        if (value.contains("generating") || value.contains("generation") || value.contains("generate")) {
            return "生成中";
        }
        if (value.contains("waiting") || value.contains("queued")) return "等待中";
        if (value.contains("handed_to_candidate")) return "已转候补";
        if (value.contains("retained_protected_gap")) return "已保留保护间隙";
        if (value.contains("processing_final_roads")) return "最终道路处理中";
        if (value.contains("merging_final_network") || value.contains("finalizing_network")
                || value.contains("flushing_road_data")) return "最终网络收尾中";
        if (value.contains("no_candidate") || value.contains("candidate_unavailable")) return "无可用候补";
        // ★ 注意：awaiting_candidate 含子串 "waiting"，会被上面那条 waiting 分支
        //   先吃掉，所以这一支对它实际不可达（本次重构保持原行为，未修）。
        if (value.contains("awaiting_candidate") || value.contains("pending_candidate")) return "待候补";
        if (value.contains("exhausted")) return "候补已耗尽";
        if (value.contains("finished") || value.contains("complete")) return "已完成";
        return operation;
    }

    /**
     * 操作名的人类可读标签。
     *
     * <p>大多数操作标识原样显示即可，只有玩家真会停留的那几个需要翻译。</p>
     */
    public static String operationLabel(String operation) {
        String value = printable(operation);
        return switch (value) {
            case "waiting_for_wall_prewarm" -> "等待长城完成";
            default -> value;
        };
    }

    /** 地形采样模式的显示名。 */
    public static Component samplingModeLabel(TerrainSamplingMode mode) {
        return Component.translatable(
                "config.roadweaver.terrain_sampling_mode.option."
                        + mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * 「已用时间」那一行。
     *
     * <p>只显示已用时间、<b>不显示剩余估算</b>：早期阶段剩余时间不可靠，
     * 显示一个乱跳的数字比不显示更让人焦虑。这条取舍写在渲染器的注释里
     * 已经很久了，现在它有了测试。</p>
     */
    public static Component etaLine(long elapsedMillis) {
        return Component.translatable("gui.roadweaver.initgen.elapsed_only",
                formatDuration(elapsedMillis));
    }

    /**
     * 一条路线阶段的描述行。
     *
     * <p>状态优先级：生成中 &gt; 待处理（仅当不是生成中）&gt; 已完成但有失败
     * ⇒ 「已结束（含失败）」。最后一条是为了不把「部分失败」显示成「已完成」。</p>
     */
    public static Component routePhaseLine(String key, RoutePhaseProgress progress) {
        RoutePhaseProgress safe = progress == null ? RoutePhaseProgress.zero() : progress;
        String step = safe.samplingStep() > 0 ? Integer.toString(safe.samplingStep()) : "-";
        String status = statusLabel(safe.status());
        if (safe.generating() > 0) {
            status = "生成中";
        } else if (safe.pending() > 0 && !"生成中".equals(status)) {
            status = "待处理";
        } else if (safe.failed() > 0 && "已完成".equals(status)) {
            status = "已结束（含失败）";
        }
        return Component.translatable(key, safe.total(), safe.generating(), safe.done(), safe.failed(),
                safe.retained(), safe.handedOff(), safe.pending(), status, step);
    }

    /**
     * 详情区会占几行。
     *
     * <p>渲染器要先知道这个数才能算出面板高度，所以它是<b>布局</b>的一部分，
     * 但它只依赖阶段与回退原因两件事，和绘制无关，所以放这里。</p>
     */
    /** 只把真实经过的分支列入步进器，未来公共阶段保留空心节点。 */
    public static java.util.List<InitialGenerationStage> displayStages(java.util.List<InitialGenerationStage> visited) {
        var result = new java.util.ArrayList<InitialGenerationStage>(visited);
        for (var stage : java.util.List.of(InitialGenerationStage.ROAD_GENERATION,
                InitialGenerationStage.POST_PROCESSING, InitialGenerationStage.FINISHED)) {
            if (!result.contains(stage)) result.add(stage);
        }
        return java.util.List.copyOf(result);
    }

    public static int detailLineCount(InitialGenerationStage stage, String fallbackReason) {
        boolean showDebug = stage == InitialGenerationStage.ROAD_GENERATION
                || stage == InitialGenerationStage.POST_PROCESSING;
        boolean showFallback = fallbackReason != null && !fallbackReason.isBlank();
        // summary + 2 route phases + mode + step + device + tiles + throughput
        return 8 + (showDebug ? 2 : 0) + (showFallback ? 1 : 0);
    }
}
