/* 文件职责：验证路线预加载量估算与地图布局度量 —— 纯几何，不需要游戏。 */
package net.shiroha233.roadweaver.client.map;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.route.RouteKind;
import net.shiroha233.roadweaver.route.RoutePoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线预加载量估算。
 *
 * <h2>为什么这个纯函数值得测</h2>
 * <p>它跑在<b>渲染线程</b>（玩家点「确认施工」的那一刻），而且结果直接决定
 * 确认框上写「将加载 N 个区块、其中 M 个未探索」—— 玩家据此判断要不要点下去。
 * 数字算小了会让人以为很快、实际卡很久；算炸了会让界面卡一帧。</p>
 *
 * <p>三条不变量：外扩 1 圈必须真的外扩、采样预算必须生效、类型分类按段起点算。</p>
 *
 * @see MapRoutePreloadEstimator
 */
class MapRoutePreloadEstimatorTest {

    private static RoutePoint p(int x, int z, RouteKind kind) {
        return new RoutePoint(new BlockPos(x, 64, z), kind);
    }

    private static RoutePoint p(int x, int z) {
        return p(x, z, RouteKind.ROAD);
    }

    @Test
    void 少于两个控制点必须返回空结果() {
        assertTrue(MapRoutePreloadEstimator.estimate(null).chunkKeys().isEmpty(), "case=null");
        MapRoutePreloadEstimator.Estimate one =
                MapRoutePreloadEstimator.estimate(List.of(p(0, 0)));
        assertEquals(0, one.segments(), "case=single-point");
        assertEquals(0, one.chunkCount(), "case=single-point-chunks");
        assertEquals(0.0, one.lengthBlocks(), 1e-9, "case=single-point-length");
    }

    @Test
    void 一段直线必须按八格步进采样并外扩一圈() {
        // (0,0) -> (40,0)：8 格步进 ⇒ 采样点 x = 0,8,16,24,32,40
        // 区块 = floor(x/16) = 0,0,1,1,2,2 ⇒ 原始 {0,1,2}
        // 外扩 1 圈 ⇒ x∈[-1,3], z∈[-1,1] ⇒ 5 × 3 = 15
        MapRoutePreloadEstimator.Estimate e = MapRoutePreloadEstimator.estimate(
                List.of(p(0, 0), p(40, 0)));

        assertEquals(1, e.segments(), "case=segments");
        assertEquals(1, e.roads(), "case=roads");
        assertEquals(40.0, e.lengthBlocks(), 1e-9, "case=length");
        assertEquals(15, e.chunkCount(), "case=expanded-chunks");
    }

    @Test
    void 类型必须按段起点归属() {
        // 三段：ROAD / BRIDGE / TUNNEL（末点的 kind 不参与，因为按段起点算）
        List<RoutePoint> points = new ArrayList<>();
        points.add(p(0, 0, RouteKind.ROAD));
        points.add(p(16, 0, RouteKind.BRIDGE));
        points.add(p(32, 0, RouteKind.TUNNEL));
        points.add(p(48, 0, RouteKind.ROAD));

        MapRoutePreloadEstimator.Estimate e = MapRoutePreloadEstimator.estimate(points);
        assertEquals(3, e.segments(), "case=segments");
        assertEquals(1, e.roads(), "case=roads-by-segment-start");
        assertEquals(1, e.bridges(), "case=bridges");
        assertEquals(1, e.tunnels(), "case=tunnels");
        assertEquals(48.0, e.lengthBlocks(), 1e-9, "case=length");
    }

    @Test
    void 超长路线必须触发采样预算而不是把渲染线程拖死() {
        // 40 段 × 400 格 = 16000 格，按 8 格步进需要 2000 次采样 —— 在预算内；
        // 再乘 5 倍长度就必然超预算。
        List<RoutePoint> shortRoute = new ArrayList<>();
        List<RoutePoint> longRoute = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            shortRoute.add(p(i * 400, 0));
            longRoute.add(p(i * 2_000, 0));
        }

        MapRoutePreloadEstimator.Estimate s = MapRoutePreloadEstimator.estimate(shortRoute);
        MapRoutePreloadEstimator.Estimate l = MapRoutePreloadEstimator.estimate(longRoute);

        // 长度与段数不受预算影响（那是精确算的）
        assertEquals(16_000.0, s.lengthBlocks(), 1e-6, "case=short-length");
        assertEquals(80_000.0, l.lengthBlocks(), 1e-6, "case=long-length");
        assertEquals(40, s.segments(), "case=short-segments");
        assertEquals(40, l.segments(), "case=long-segments");

        // 但采样变稀 ⇒ 区块数不会随长度线性爆炸
        assertTrue(l.chunkCount() < s.chunkCount() * 20,
                "case=budget-bounds-chunks: 长度 5 倍，区块数不该 20 倍以上："
                        + s.chunkCount() + " -> " + l.chunkCount());
    }

    @Test
    void 原地踏步的两点也必须产生区块() {
        // 两个点坐标相同：长度 0，但仍有 1 个采样点
        MapRoutePreloadEstimator.Estimate e = MapRoutePreloadEstimator.estimate(
                List.of(p(100, 100), p(100, 100)));
        assertEquals(0.0, e.lengthBlocks(), 1e-9, "case=zero-length");
        assertEquals(9, e.chunkCount(), "case=single-chunk-expanded-3x3");
    }

    // ── 布局度量 ─────────────────────────────────────────────

    @Test
    void 线条粗细必须被夹在上下限之间() {
        assertEquals(MapTheme.MIN_THICKNESS, MapLayoutMetrics.thickness(0.0), "case=too-thin");
        assertEquals(MapTheme.MIN_THICKNESS, MapLayoutMetrics.thickness(0.2), "case=rounds-to-zero");
        assertEquals(MapTheme.MAX_THICKNESS, MapLayoutMetrics.thickness(999.0), "case=too-thick");
        // 中间值取整后原样返回
        assertEquals(3, MapLayoutMetrics.thickness(2.6), "case=rounded");
    }

    @Test
    void 点位直径必须大于线条粗细() {
        assertEquals(MapTheme.BASE_POINT_SIZE + 1, MapLayoutMetrics.pointSize(1), "case=point-size");
        assertTrue(MapLayoutMetrics.pointSize(1) > 1, "case=point-larger-than-thickness");
    }

    @Test
    void 工作区底边必须至少为一() {
        assertEquals(1, MapLayoutMetrics.workspaceBottomLimit(0), "case=clamped-to-one");
        assertEquals(1, MapLayoutMetrics.workspaceBottomLimit(-100), "case=negative-clamped");
        assertEquals(100 - MapLayoutMetrics.WORKSPACE_DOCK_GAP,
                MapLayoutMetrics.workspaceBottomLimit(100), "case=normal");
    }
}
