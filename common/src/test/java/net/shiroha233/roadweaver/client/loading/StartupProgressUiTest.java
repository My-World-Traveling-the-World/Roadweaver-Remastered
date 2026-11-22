package net.shiroha233.roadweaver.client.loading;

import java.nio.file.Files;
import java.nio.file.Path;
import net.shiroha233.roadweaver.generation.progress.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 直接驱动正式进度发布器；另验真实入口接线，不把纯布局测试当客户端启动证明。 */
class StartupProgressUiTest {
    @AfterEach void clear() { InitialGenerationProgressTracker.reset(); }

    @Test void corridorCheckKeepsPathStageAndPublishesItsOwnLabel() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.COARSE_PATHING, "pathing");
        InitialGenerationProgressTracker.enterCorridorCheck();
        var s = InitialGenerationProgressTracker.snapshot(true);
        assertEquals(InitialGenerationStage.COARSE_PATHING, s.stage());
        assertEquals("gui.roadweaver.initgen.stage.corridor_check", s.stageLabel());
        assertEquals("predicting_road_corridor_structures", s.currentOperation());
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.ROAD_GENERATION, "roads");
        InitialGenerationProgressTracker.enterCorridorCheck();
        assertEquals("roads", InitialGenerationProgressTracker.snapshot(true).currentOperation());
    }

    @Test void expansionStartsAnExplicitNewRoundAndDoesNotKeepOldChecks() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.beginPlanningRound(1);
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.EXACT_SAMPLING, "sampling");
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.EXACT_PATHING, "pathing");
        assertEquals(3, InitialGenerationProgressTracker.snapshot(true).visitedStages().size());
        InitialGenerationProgressTracker.beginPlanningRound(2);
        var s = InitialGenerationProgressTracker.snapshot(true);
        assertEquals(2, s.planningRound());
        assertEquals(java.util.List.of(InitialGenerationStage.PLANNING), s.visitedStages());
        InitialGenerationProgressTracker.reset();
        assertEquals(0, InitialGenerationProgressTracker.snapshot(true).planningRound());
    }

    @Test void coarseTopologyShowsDiscoveredEndpointsWithoutPretendingToKnowItsProgress() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.beginPlanningRound(1);
        InitialGenerationProgressTracker.setPlanningTilePlan(1, "discovering_structures");
        InitialGenerationProgressTracker.beginCoarseTopology(35);
        var topology = InitialGenerationProgressTracker.snapshot(true);
        assertEquals(InitialGenerationStage.PLANNING, topology.stage());
        assertEquals("gui.roadweaver.initgen.stage.coarse_topology", topology.stageLabel());
        assertEquals("已收集端点 35", topology.subProgressLabel());
        assertEquals(1, topology.planningTilesDone());
        assertEquals(25, topology.stagePercent(), "已完成的是端点扫描，粗拓扑尚未结束");
        InitialGenerationProgressTracker.completePlanningSnapshots("structure_topology_ready");
        assertEquals(100, InitialGenerationProgressTracker.snapshot(true).stagePercent());
        // 扩域和新世界都不应把上一轮端点或里程碑带进新一轮。
        InitialGenerationProgressTracker.beginPlanningRound(2);
        assertEquals(0, InitialGenerationProgressTracker.snapshot(true).planningStartsTotal());
        assertEquals(0, InitialGenerationProgressTracker.snapshot(true).stagePercent());
    }

    @Test void unexecutedExactBranchIsNeverCheckedByCoarsePathing() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.COARSE_SAMPLING, "sampling");
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.COARSE_PATHING, "pathing");
        var display = LoadingOverlayText.displayStages(InitialGenerationProgressTracker.snapshot(true).visitedStages());
        assertEquals(java.util.List.of(InitialGenerationStage.PLANNING, InitialGenerationStage.COARSE_SAMPLING,
                InitialGenerationStage.COARSE_PATHING, InitialGenerationStage.ROAD_GENERATION,
                InitialGenerationStage.POST_PROCESSING, InitialGenerationStage.FINISHED), display);
    }

    @Test void cardFitsEveryLogicalScreenWithoutDroppingRows() {
        // 从小窗口到4K逻辑尺寸遍历；先断言实际有内容，再核对四条屏幕边界。
        for (int width : new int[] {160, 240, 320, 426, 854, 1920}) {
            for (int height : new int[] {90, 180, 240, 360, 480, 1080}) {
                for (int rows : new int[] {1, 7, 8, 10, 11}) {
                    var layout = LoadingCardLayout.fit(width, height, 360, rows);
                    assertTrue(layout.width() > 0 && layout.height() > 0);
                    assertTrue(layout.scale() > 0 && layout.scale() <= 1);
                    assertTrue(layout.x() >= 0 && layout.y() >= 0);
                    assertTrue(layout.x() + layout.width() * layout.scale() <= width + 0.001);
                    assertTrue(layout.y() + layout.height() * layout.scale() <= height + 0.001);
                    assertEquals(82 + rows * 12 + layout.bandHeight(), layout.height());
                }
            }
        }
        assertEquals(1F, LoadingCardLayout.fit(854, 480, 360, 8).scale());
        assertEquals(120, LoadingCardLayout.fit(854, 480, 360, 8).bandHeight());
    }

    @Test void productionUsesCorridorRoundAndResponsivePresentation() throws Exception {
        var root = Path.of("src/main/java/net/shiroha233/roadweaver");
        var corridor = Files.readString(root.resolve("pathlogic/structures/SnapshotOrchestrator.java"));
        assertTrue(corridor.contains("InitialGenerationProgressTracker.enterCorridorCheck();"));
        assertFalse(corridor.contains("InitialGenerationStage.PLANNING, \"predicting_road_corridor_structures\""));
        var planning = Files.readString(root.resolve("planning/AsyncPlanningOps.java"));
        assertTrue(planning.contains("InitialGenerationProgressTracker.beginPlanningRound(state.rounds());"));
        assertTrue(planning.contains("InitialGenerationProgressTracker.beginCoarseTopology(points.size());"));
        var renderer = Files.readString(root.resolve("client/loading/LoadingGenerationOverlayRenderer.java"));
        assertTrue(renderer.contains("LoadingCardLayout.fit(sw, sh, 360, detailLines)"));
        assertTrue(renderer.contains("LoadingOverlayText.displayStages(snapshot.visitedStages())"));
        assertTrue(renderer.contains("LoadingOverlayText.detailLineCount(snapshot.stage(), fallbackReason)"));
        assertTrue(renderer.contains("drawDetail(graphics, font, snapshot, session, fallbackReason,"));
        assertTrue(renderer.contains("graphics.pose().scale(layout.scale(), layout.scale(), 1F)"));
    }
}
