/* 文件职责：给体积规划 visit() 补行为测试 —— 它在 2026-11-15 之前零覆盖。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.construction.RoadConstructionVolumePlanner.ColumnVolume;
import net.shiroha233.roadweaver.pathlogic.construction.RoadConstructionVolumePlanner.Context;
import net.shiroha233.roadweaver.pathlogic.construction.RoadConstructionVolumePlanner.Kind;
import net.shiroha233.roadweaver.pathlogic.construction.RoadConstructionVolumePlanner.TemplateBridgePlan;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 体积规划的行为测试（2026-11-15 新增）。
 *
 * <h2>为什么现在才补</h2>
 * <p>{@code RoadConstructionVolumePlanner.visit} 在此之前**没有任何行为测试** ——
 * 它唯一的消费方是落盘前校验器，而校验器本身的测试也没覆盖它。
 * 2026-11-15 删掉 {@code SHOULDER} 产出时才发现：这是「改错了也没人拦」的地带。</p>
 *
 * <h2>这组测试守什么</h2>
 * <ol>
 *   <li><b>覆盖完整性</b>：平坦地形上，每一站的每个 {@code positions()} 列都要有体积，
 *       不能漏列（漏列 = 校验看不见那些方块）。</li>
 *   <li><b>不再产出路肩</b>：施工端明确「Road shoulders are intentionally disabled」，
 *       规划侧也不该再算。这条钉住 2026-11-15 的删除。</li>
 *   <li><b>visit 的返回值契约</b>：访问器返回 {@code false} 时 visit 立即停止。</li>
 *   <li><b>输入校验</b>：{@code targetY} 长度与站数不符时整体返回 false（不半途而废）。</li>
 * </ol>
 *
 * <h2>刻意不测的东西</h2>
 * <p>桥、隧道、模板桥这些分支依赖真实世界查询（水位、结构快照），
 * 用 fake Context 测出来的只是「我写的 fake 对不对」，不是生产行为。
 * 这里只测与地形无关的平坦地面主路径 —— 而它恰好就是路肩被删除的那条路径。</p>
 */
class VolumePlannerVisitTest {

    /**
     * {@code new RoadData(...)} 会触发它的静态 CODEC 初始化，
     * 进而触碰 {@code BlockState} 与内置注册表 —— 必须先引导 MC 注册表。
     * 这是本测试**唯一**的 MC 环境依赖；visit 本身是纯几何、不读世界。
     */
    @BeforeAll
    static void bootstrapRegistries() {
        // 顺序不能反：tryDetectVersion 先给出 MC 版本，
        // 后面的 Bootstrap 才能在 Util.doFetchChoiceType 里正常解析。
        // 参照 RoadChunkPlanCompilerReuseOwnershipTest 的可用写法。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 平坦地形、无水系、无模板桥的 Context —— 让 visit 走纯主路径。 */
    private static Context flatContext(int groundY) {
        return new Context(
                ConfigService.get() == null ? null : ConfigService.get().snapshot(),
                (x, z) -> groundY,              // terrain
                (x, z) -> groundY,              // foundation
                (x, z) -> false,                // watercourse：全程无河
                station -> new TemplateBridgePlan(List.of(), false));
    }

    /** 造一条沿 +X 方向的直线路，每站 3 列（宽度 3）。 */
    private static RoadData straightRoad(int stations, int groundY) {
        List<RoadSegmentPlacement> segments = new ArrayList<>(stations);
        List<Integer> targetY = new ArrayList<>(stations);
        for (int i = 0; i < stations; i++) {
            int cx = i * 4;
            List<BlockPos> positions = List.of(
                    new BlockPos(cx, groundY, -1),
                    new BlockPos(cx, groundY, 0),
                    new BlockPos(cx, groundY, 1));
            segments.add(new RoadSegmentPlacement(new BlockPos(cx, groundY, 0), positions));
            targetY.add(groundY);
        }
        return new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY, 1L, 2L);
    }

    private static List<ColumnVolume> collect(RoadData road, Context context) {
        List<ColumnVolume> out = new ArrayList<>();
        boolean completed = RoadConstructionVolumePlanner.visit(road, context, volume -> {
            out.add(volume);
            return true;
        });
        assertTrue(completed, "平坦地形上 visit 应当正常走完，不应提前中止");
        return out;
    }

    @Test
    void 每一站的每一列都要有体积不能漏列() {
        int groundY = 64;
        RoadData road = straightRoad(4, groundY);
        List<ColumnVolume> volumes = collect(road, flatContext(groundY));

        // ★ 只收集 Kind.ROAD 的列，不能收集「任意 Kind」。
        //   这一点踩过：第一版断言「列被任意 Kind 覆盖」，
        //   结果故意跳过 ROAD 后 ROAD_FALLING_CAP 仍然覆盖同一列，负控抓不到。
        //   真正要保证的是「路面本体不能漏列」，所以必须按 Kind 精确收。
        Set<Long> roadColumns = new LinkedHashSet<>();
        for (ColumnVolume v : volumes) {
            if (v.kind() != Kind.ROAD) continue;
            roadColumns.add(((long) v.x() << 32) ^ (v.z() & 0xffffffffL));
        }

        // 每一站 positions() 里的列都必须是 ROAD 体积
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            for (BlockPos column : segment.positions()) {
                long key = ((long) column.getX() << 32) ^ (column.getZ() & 0xffffffffL);
                assertTrue(roadColumns.contains(key),
                        "列 (" + column.getX() + "," + column.getZ() + ") 属于路面，"
                                + "却没有 ROAD 体积 —— 校验器会看不见这一列"
                                + "（注意：别的 Kind 覆盖同一列不算数）。");
            }
        }
    }

    @Test
    void 不再产出路肩体积() {
        int groundY = 64;
        List<ColumnVolume> volumes = collect(straightRoad(3, groundY), flatContext(groundY));
        // ★ 2026-11-15 补：下面那个循环在 volumes 为空时一次都不执行 ⇒ 测试会**真空转绿**。
        //   同文件的「高度范围包住目标高度」有 sawRoad 兜底，唯独这条没有。
        assertFalse(volumes.isEmpty(),
                "构造前提：平坦直路上必须至少产出一个体积，否则下面的断言空转");

        for (ColumnVolume v : volumes) {
            assertFalse("SHOULDER".equals(v.kind().name()),
                    "出现了路肩体积 —— 但施工端明确不建路肩"
                            + "（SegmentPaver: Road shoulders are intentionally disabled），"
                            + "规划侧再算就是纯浪费采样预算。");
        }
    }

    @Test
    void 平坦地形上路面体积的高度范围包住目标高度() {
        int groundY = 64;
        List<ColumnVolume> volumes = collect(straightRoad(3, groundY), flatContext(groundY));

        boolean sawRoad = false;
        for (ColumnVolume v : volumes) {
            if (v.kind() != Kind.ROAD) continue;
            sawRoad = true;
            assertTrue(v.minY() <= groundY && v.maxY() >= groundY - 1,
                    "路面列 (" + v.x() + "," + v.z() + ") 的体积范围 ["
                            + v.minY() + "," + v.maxY() + "] 没包住地面/目标高度 "
                            + groundY + " —— 校验会看不到需要处理的方块。");
        }
        assertTrue(sawRoad, "平坦直路上应当至少产出一个 ROAD 体积");
    }

    @Test
    void 访问器返回false时立即停止() {
        int groundY = 64;
        RoadData road = straightRoad(8, groundY);
        int[] visited = {0};

        boolean completed = RoadConstructionVolumePlanner.visit(
                road, flatContext(groundY), volume -> {
                    visited[0]++;
                    return false;      // 第一个就喊停
                });

        assertFalse(completed, "访问器拒绝后 visit 必须如实返回 false");
        assertEquals(1, visited[0],
                "访问器已经返回 false，visit 不应再继续访问后面的列");
    }

    @Test
    void targetY长度与站数不符时整体拒绝() {
        int groundY = 64;
        RoadData good = straightRoad(4, groundY);
        // 故意把 targetY 截短一格
        RoadData broken = new RoadData(good.width(), good.roadType(),
                good.materials(), good.slabMaterials(), good.roadSegmentList(),
                good.spans(), good.targetY().subList(0, 3),
                good.ownerA2dKey(), good.ownerB2dKey());

        List<ColumnVolume> out = new ArrayList<>();
        boolean completed = RoadConstructionVolumePlanner.visit(
                broken, flatContext(groundY), volume -> {
                    out.add(volume);
                    return true;
                });

        assertFalse(completed, "targetY 与站数不一致时必须整体返回 false");
        assertTrue(out.isEmpty(),
                "拒绝的输入不应产出任何体积 —— 半途而废比直接拒绝更难排查");
    }

    @Test
    void 空道路与null参数安全返回false() {
        Context context = flatContext(64);
        assertFalse(RoadConstructionVolumePlanner.visit(null, context, v -> true),
                "road 为 null 应返回 false");
        assertFalse(RoadConstructionVolumePlanner.visit(straightRoad(2, 64), null, v -> true),
                "context 为 null 应返回 false");
        assertFalse(RoadConstructionVolumePlanner.visit(straightRoad(2, 64), context, null),
                "visitor 为 null 应返回 false");
    }
}