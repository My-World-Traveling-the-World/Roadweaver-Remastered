/* 文件职责：验证加载界面纯展示逻辑 —— 时长、ETA、速率、状态标签、设备名回退。 */
package net.shiroha233.roadweaver.client.loading;

import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 加载覆盖层的展示逻辑。
 *
 * <h2>为什么这些值得单独测</h2>
 * <p>它们以前藏在 585 行的渲染器里，要测一下就得造一个 Minecraft 客户端，
 * 所以<b>从来没有测试</b>。而数值换算恰恰是最容易错的地方：
 * 百分比风格的时间格式、向上取整、分钟进位、负数保护、
 * 以及状态标签那串 if 的<b>判定顺序</b>。</p>
 *
 * <p>现在它们只收普通数据类型，可以纯 JVM 跑。</p>
 *
 * @see LoadingOverlayText
 */
class LoadingOverlayTextTest {

    // ── 时长格式 ─────────────────────────────────────────────

    @Test
    void 时长格式必须不足一分钟用秒超过一分钟用分秒() {
        assertEquals("0s", LoadingOverlayText.formatDuration(0L));
        assertEquals("0s", LoadingOverlayText.formatDuration(999L), "不足 1 秒按 0 秒");
        assertEquals("1s", LoadingOverlayText.formatDuration(1000L));
        assertEquals("59s", LoadingOverlayText.formatDuration(59_999L), "59.999 秒还没进位");
        assertEquals("1:00", LoadingOverlayText.formatDuration(60_000L));
        assertEquals("1:05", LoadingOverlayText.formatDuration(65_000L), "秒数必须补零到两位");
        assertEquals("60:00", LoadingOverlayText.formatDuration(3_600_000L));
        assertEquals("61:01", LoadingOverlayText.formatDuration(3_661_000L));
    }

    @Test
    void 负数时长必须显示为零而不是负数() {
        // 时钟回拨或计时器未初始化都可能产出负值。
        // 显示 -1s 会让玩家以为卡住了。
        assertEquals("0s", LoadingOverlayText.formatDuration(-1L));
        assertEquals("0s", LoadingOverlayText.formatDuration(-60_000L));
    }

    // ── 剩余时间估算 ─────────────────────────────────────────

    @Test
    void 速率未知时必须显示双横线而不是零() {
        // 早期阶段速率为 0。若显示「剩余 0 秒」，玩家会以为马上好，然后一直等。
        assertEquals("--", LoadingOverlayText.exactSamplingEta(0.0, 1000L, 0L));
        assertEquals("--", LoadingOverlayText.exactSamplingEta(-5.0, 1000L, 0L));
    }

    @Test
    void 剩余时间必须向上取整且不出现负数() {
        assertEquals("10s", LoadingOverlayText.exactSamplingEta(100.0, 1000L, 0L));
        assertEquals("4s", LoadingOverlayText.exactSamplingEta(3.0, 10L, 0L),
                "10/3 = 3.33 秒必须向上取整成 4s");
        assertEquals("0s", LoadingOverlayText.exactSamplingEta(100.0, 1000L, 1000L),
                "已完成时剩余为 0");
        assertEquals("0s", LoadingOverlayText.exactSamplingEta(100.0, 1000L, 2000L),
                "已完成数超过总数时也不能出负数");
        assertEquals("1:40", LoadingOverlayText.exactSamplingEta(1.0, 100L, 0L),
                "100 秒应显示 1:40 而不是 100s");
    }

    @Test
    void 批次速率必须处理没有批次数据的情况() {
        assertEquals("--", LoadingOverlayText.exactBatchRate(0, 0L));
        assertEquals("--", LoadingOverlayText.exactBatchRate(1024, -1L));
        assertEquals("2048", LoadingOverlayText.exactBatchRate(1024, 500L));
        assertEquals("0", LoadingOverlayText.exactBatchRate(1, 10_000L),
                "极小速率取整后可能为 0，这是 %.0f 的既定行为");
    }

    // ── 空值与设备名 ─────────────────────────────────────────

    @Test
    void 空值必须统一显示成短横线() {
        assertEquals("-", LoadingOverlayText.printable(null));
        assertEquals("-", LoadingOverlayText.printable(""));
        assertEquals("-", LoadingOverlayText.printable("   "));
        assertEquals("CPU", LoadingOverlayText.printable("CPU"));
    }

    @Test
    void 设备名缺失时必须回退到后端名() {
        assertEquals("RTX 4070", LoadingOverlayText.printableDevice("RTX 4070", "GPU"));
        assertEquals("GPU", LoadingOverlayText.printableDevice(null, "GPU"),
                "没有设备名时显示后端，比显示短横线有用");
        assertEquals("CPU", LoadingOverlayText.printableDevice("", "CPU"));
        assertEquals("-", LoadingOverlayText.printableDevice(null, null));
    }

    // ── 状态标签（判定顺序）──────────────────────────────────

    @Test
    void 同时含采样与规划时必须优先显示两者都有() {
        // 这是判定顺序的关键：sampling_planning 同时含两个关键词，
        // 若先判单个就永远显示「采样中」。
        assertEquals("采样/规划中", LoadingOverlayText.statusLabel("sampling_planning"), "case=both");
        assertEquals("采样中", LoadingOverlayText.statusLabel("sampling"), "case=sampling_only");
        assertEquals("规划中", LoadingOverlayText.statusLabel("planning"), "case=planning_only");
    }

    @Test
    void 状态标签必须覆盖各类操作标识() {
        // 消息用 ASCII：控制台是 GBK，中文断言失败信息会被糊成问号，
        // 带上 ASCII 标签才能在失败时立刻看出是哪一条。
        assertEquals("-", LoadingOverlayText.statusLabel(null), "case=null");
        assertEquals("-", LoadingOverlayText.statusLabel(""), "case=blank");
        assertEquals("-", LoadingOverlayText.statusLabel("idle"), "case=idle");
        assertEquals("规划中", LoadingOverlayText.statusLabel("structure_scan"), "case=structure_scan");
        assertEquals("规划中", LoadingOverlayText.statusLabel("corridor_build"), "case=corridor_build");
        assertEquals("规划中", LoadingOverlayText.statusLabel("obstacle_discovering"), "case=obstacle_discovering");
        assertEquals("生成中", LoadingOverlayText.statusLabel("generating"), "case=generating");
        assertEquals("等待中", LoadingOverlayText.statusLabel("queued"), "case=queued");
        assertEquals("已转候补", LoadingOverlayText.statusLabel("handed_to_candidate"), "case=handed_to_candidate");
        assertEquals("已保留保护间隙", LoadingOverlayText.statusLabel("retained_protected_gap"), "case=retained_protected_gap");
        assertEquals("最终道路处理中", LoadingOverlayText.statusLabel("processing_final_roads"), "case=processing_final_roads");
        assertEquals("最终网络收尾中", LoadingOverlayText.statusLabel("merging_final_network"), "case=merging_final_network");
        assertEquals("无可用候补", LoadingOverlayText.statusLabel("no_candidate"), "case=no_candidate");
        // ★ 既存缺陷（本次纯重构保持原样，未改行为）：
        //   "awaiting_candidate" 里含子串 "waiting"，会先命中上面那条 waiting 分支，
        //   所以它永远显示「等待中」，后面那条 awaiting_candidate 分支实际不可达。
        //   纯显示问题、不影响生成；留在这里是为了将来要修时有据可依。
        assertEquals("等待中", LoadingOverlayText.statusLabel("awaiting_candidate"),
                "case=awaiting_shadowed_by_waiting");
        assertEquals("待候补", LoadingOverlayText.statusLabel("pending_candidate"),
                "case=pending_candidate");
        assertEquals("候补已耗尽", LoadingOverlayText.statusLabel("exhausted"), "case=exhausted");
        assertEquals("已完成", LoadingOverlayText.statusLabel("finished"), "case=finished");
        assertEquals("已完成", LoadingOverlayText.statusLabel("complete"), "case=complete");
    }

    @Test
    void 无法识别的操作必须原样返回() {
        assertEquals("some_new_operation",
                LoadingOverlayText.statusLabel("some_new_operation"),
                "新增操作标识不该被吞掉，原样显示才能被发现");
    }

    @Test
    void 只有玩家真会停留的操作才翻译() {
        assertEquals("等待长城完成", LoadingOverlayText.operationLabel("waiting_for_wall_prewarm"), "case=wall");
        assertEquals("generating", LoadingOverlayText.operationLabel("generating"),
                "其余操作标识原样显示");
        assertEquals("-", LoadingOverlayText.operationLabel(null));
    }

    // ── 布局行数 ─────────────────────────────────────────────

    @Test
    void 详情行数必须随阶段与回退原因变化() {
        assertEquals(7, LoadingOverlayText.detailLineCount(null, null));
        assertEquals(7, LoadingOverlayText.detailLineCount(null, ""));
        assertEquals(8, LoadingOverlayText.detailLineCount(null, "no_opencl"));
        assertEquals(9, LoadingOverlayText.detailLineCount(
                InitialGenerationStage.ROAD_GENERATION, null));
        assertEquals(10, LoadingOverlayText.detailLineCount(
                InitialGenerationStage.ROAD_GENERATION, "no_opencl"));
        assertEquals(9, LoadingOverlayText.detailLineCount(
                InitialGenerationStage.POST_PROCESSING, null));
    }
}
