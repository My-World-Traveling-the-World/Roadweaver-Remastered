/* 文件职责：批 4 主入口 connectPlannedBatch 的契约测试（探边界用，先只测早退分支）。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ExistingRoadJunctionService.connectPlannedBatch(List)} 的契约测试。
 *
 * <p>这是 842 行主入口里<b>不依赖 {@code ServerLevel}</b> 的重载。
 * 它在两条早退分支之后才会走到 {@code ConfigService.get()}，
 * 本类先钉住「早退分支的返回契约」，并探明「2 条以上路」在单测环境里的真实行为。</p>
 */
class ExistingRoadJunctionBatchEntryTest {

    private static RoadData road(int x0, int z, int n) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos center = new BlockPos(x0 + i * 4, 64, z);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments, List.of(), heights, 1L, 2L);
    }

    @Test
    void null_输入返回空结果() {
        ExistingRoadJunctionService.MergeBatchResult r =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(null, null);
        assertNotNull(r, "绝不能返回 null —— 调用方 InitialRoutePersistOps 直接取 .roads()");
        assertTrue(r.roads().isEmpty(), "null 输入必须返回空路表");
        assertTrue(r.refinedRoadFingerprints().isEmpty(), "没有处理任何路时不该有指纹");
    }

    @Test
    void 空批次返回空结果() {
        ExistingRoadJunctionService.MergeBatchResult r =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(null, List.of());
        assertTrue(r.roads().isEmpty(), "空批次必须返回空");
        assertTrue(r.refinedRoadFingerprints().isEmpty(), "空批次不该产生指纹");
    }

    @Test
    void 单条路在没有_level_时原样返回() {
        RoadData only = road(0, 0, 8);
        ExistingRoadJunctionService.MergeBatchResult r =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(null, List.of(only));
        assertEquals(1, r.roads().size(), "单路没有可合并对象，必须原样返回 1 条");
        assertEquals(only, r.roads().get(0), "而且必须是同一条路的内容（不许被重建）");
        assertTrue(r.refinedRoadFingerprints().isEmpty(), "没发生合并就不该有 refined 指纹");
    }

    @Test
    void 两条相距很远的路都保留() {
        // 探边界时先确认：无 level 且 2 条路**不会抛异常**（ConfigService 在单测里可用）。
        List<RoadData> two = List.of(road(0, 0, 8), road(0, 2000, 8));
        ExistingRoadJunctionService.MergeBatchResult r =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(null, two);
        assertEquals(2, r.roads().size(),
                "相距 2000 格的两条路没有可合并对象，必须都保留 —— "
                        + "若这里变小，说明合并判据变激进了");
    }

    // ══════════ 「不足 5 站 = 退化路」的四点一致约定 ══════════
    //
    // connectPlannedBatchDetailed 用 `road.roadSegmentList().size() >= 5`
    // 过滤输入，然后**返回过滤后的列表**（`List.copyOf(current)`）。
    // 调用方 InitialRoutePersistOps:154 拿 `mergeBatch.roads()` 直接去持久化。
    //
    // ★ 复查结论（2026-11-15，我先误判成 bug、复查后更正）：
    //   **这不是 bug，是与校验层一致的约定。** 同一个 `< 5` 判据在全仓共 **4 处**：
    //   · RoadDataValidator:45   —— validateFinalRoad 返回 "road-too-short" 失败
    //   · RoadDataValidator:454  —— normalizeLegacyRoad 返回 null
    //   · ExistingRoadJunctionService:119  —— 本方法的过滤
    //   · ExistingRoadJunctionService:352  —— connectToExistingNetwork 早退
    //   ⇒ 不足 5 站的路本来就会被 validateFinalRoad 判死，这里只是**提前**剔掉。
    //
    // ⚠️ 真正的问题（次要）：这个 `5` 是**裸魔数**，4 处重复、**没有具名常量、
    //   没有注释**。任何一处单独改动都会与其余三处漂移。
    //   本节把这条契约变成显式被测契约：以后谁改阈值或改「丢弃/保留」语义，
    //   都必须先改这里并说明理由。

    @Test
    void 不足五站的路会被静默剔除_这是当前契约() {
        RoadData longRoad = road(0, 0, 8);        // 8 站，保留
        RoadData shortRoad = road(0, 500, 4);     // 4 站，会被剔除
        ExistingRoadJunctionService.MergeBatchResult r =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(
                        null, List.of(longRoad, shortRoad));
        assertEquals(1, r.roads().size(),
                "当前契约：>= 5 站才参与合并，4 站的路被剔除。"
                        + "★ 如果这里变成了 2，说明阈值被放开 —— 那就必须同时确认"
                        + "「4 站的路」在施工/持久化侧是被支持的，否则会出现半条路");
        assertEquals(longRoad, r.roads().get(0), "保留的必须是那条 8 站的路");
    }

    @Test
    void 恰好五站的路会保留_阈值是闭区间() {
        RoadData five = road(0, 0, 5);
        ExistingRoadJunctionService.MergeBatchResult r =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(null, List.of(five));
        // 单条路走早退分支，所以这里用 2 条来真正触发过滤
        RoadData other = road(0, 2000, 5);
        ExistingRoadJunctionService.MergeBatchResult r2 =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(null, List.of(five, other));
        assertTrue(r.roads().size() == 1, "单条 5 站路走早退分支、原样返回");
        assertEquals(2, r2.roads().size(),
                "判据是 `>= 5`（闭区间）：恰好 5 站必须保留。"
                        + "若这里变成 1，说明阈值被改成了 `> 5`，边界少一条");
    }

    @Test
    void 列表里的_null_元素会被剔除且不抛异常() {
        RoadData good = road(0, 0, 8);
        java.util.List<RoadData> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(good);
        ExistingRoadJunctionService.MergeBatchResult r =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(null, withNull);
        assertNotNull(r, "含 null 元素时不许整体失败");
        assertEquals(1, r.roads().size(), "null 元素必须被剔除，好路必须保留");
    }
}
