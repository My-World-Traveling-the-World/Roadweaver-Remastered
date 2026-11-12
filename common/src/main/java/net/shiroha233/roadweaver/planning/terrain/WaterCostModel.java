/* 文件职责：过水代价与临河距离场 —— 只算「过这片水要付多少」。 */
package net.shiroha233.roadweaver.planning.terrain;

import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.planning.terrain.StructureRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair.*;

// 仍留在其它同包类里的常量、代价函数与工具（包内可见）。
import static net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner.*;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.planning.terrain.StructureRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.SearchBudgetPolicy.*;
import static net.shiroha233.roadweaver.planning.terrain.RepairAcceptance.*;

/**
 * 过水代价与临河距离场。
 *
 * <h2>三档水价（0.9.012 定案）</h2>
 * <ul>
 *   <li><b>涵洞</b>（run ≤ 6 且 depth ≤ 2）：{@code run × 2} —— 便宜，鼓励直接过；</li>
 *   <li><b>窄水</b>（run ≤ narrowWaterMaxWidth）：{@code run × 20}；</li>
 *   <li><b>宽水</b>（run > 20）：{@code 600 + 80 × run} —— 20 格 400、21 格 2280，
 *       **5.7 倍断崖**，刻意让 A* 在 20/21 之间做清晰选择。</li>
 * </ul>
 *
 * <h2>★ landDetourCap 的两支取较松（真 bug 修出来的）</h2>
 * <p>陆路闸门的上限若只取「直连距离 × 系数」，而局部修复的输入本身可能已经是
 * 三倍直连的之字路，就会把<b>合格的陆路修复</b>判成绕太远、桥又冒出来。
 * 现在取 **max(直连×系数, 原走廊长×1.05)**。</p>
 *
 * <h2>临河距离场</h2>
 * <p>{@code buildRiverDistanceField} 建一张到最近河的距离图，用来给「贴河走」
 * 加一点惩罚（2026-11-05 起 radius 8→5、penalty 300→8：只防死贴河岸，
 * 让路自己稳在离水 5 格，而不是强行把路推开）。</p>
 *
 * <p>本类是从 1631 行的 {@code AccurateGradeRepairPlanner} 里<b>纯搬迁</b>出来的，
 * 价目表与距离场算法一字未改（含缩进）。</p>
 */
final class WaterCostModel {
    static boolean isWetColumn(AccurateHeightSample sample) {
        // 0.9.045（D-8）：恢复「水深 > 1 才算湿」。
        //
        // 0.9.009（cb800f5）把这里从 depth > 1 放宽成 depth >= 1，后果三条，第二条致命：
        //   ① 1 格深的水从"免费"直接进最贵档（窄水 run×20）；
        //   ② 同一判据还被陆路闸门的 wetBlocked 用（:1586）⇒ **一个 1 格深的水洼
        //      就能让闸门永久判 no-land-route**，landDetourFactor 那道"能绕就绕"
        //      的硬保证在浅水地形上整条失效；
        //   ③ 贴河走满 13 站就算桥（crossingWidthBlocks 量的是沿路长度）。
        // 恢复 > 1 之后：浅水洼重新可通行、重新免费，闸门重新工作。
        return sample != null
                && sample.worldSurfaceWg() - sample.oceanFloorWg() > 1;
    }

    /**
     * 0.9.011：三档价目（宽度 = 连续湿步数，由 {@code wetRun} 维护）。
     * <ol>
     *   <li>涵洞：{@code run <= culvertMaxStations} 且 {@code depth <= culvertMaxWaterDepth}
     *       → {@code run × culvertPer}</li>
     *   <li>窄水：{@code run <= narrowMaxWidth} → {@code run × narrowPer}（小桥，直接过）</li>
     *   <li>宽水：其余 → {@code base + per × run}（大河，重罚绕行）</li>
     * </ol>
     * {@code narrowMaxWidth <= 0} 时跳过窄水档，语义等同 0.9.010 的两档。
     */
    static double waterCrossingCost(int run, int depth, double base, double per,
                                           double culvertPer, int culvertMaxStations,
                                           int culvertMaxWaterDepth,
                                           int narrowMaxWidth, double narrowPer) {
        // 旧签名 = step 1（此时"步数"与"格数"同量纲），保持既有单测逐位不变。
        return waterCrossingCost(run, depth, base, per, culvertPer, culvertMaxStations,
                culvertMaxWaterDepth, narrowMaxWidth, narrowPer, 1);
    }

    /**
     * 0.9.053（D-12）：{@code narrowMaxWidth} 是**格**（配置项叫 narrowWaterMaxWidth），
     * 而 {@code run} 是**连续湿步数** —— 两者量纲不同。{@code step=2} 的调用点原来拿
     * 「8 步」去比「20 格」，等于把阈值当成 40 格用：同一条 21 格河，河修复路径（step1）
     * 判宽水、修坡路径（step2）判窄水，**同一条河 10.4 倍价差**。
     *
     * <p>本次只在**窄水档的比较**上按格折算（{@code run × step}）；
     * 计价乘数仍用 {@code run}（与 A* 的每步代价同量纲），涵洞档仍按
     * {@code culvertMaxStations}（语义就是"站"）比较。{@code step = 1} 时逐位不变。</p>
     */
    static double waterCrossingCost(int run, int depth, double base, double per,
                                           double culvertPer, int culvertMaxStations,
                                           int culvertMaxWaterDepth,
                                           int narrowMaxWidth, double narrowPer, int step) {
        if (run <= 0) return 0.0D;
        if (culvertMaxStations > 0 && run <= culvertMaxStations && depth <= culvertMaxWaterDepth) {
            return run * culvertPer;
        }
        int runBlocks = run * Math.max(1, step);
        if (narrowMaxWidth > 0 && runBlocks <= narrowMaxWidth) {
            return run * narrowPer;
        }
        return base + per * run;
    }

    /**
     * 0.9.011：陆路闸门的长度上限（格）。抽成纯函数是为了能被单测钉住 ——
     * 尤其是「之字形走廊」那一支：{@code findLocalRoute} 是局部修复，原走廊本身
     * 可能已是一条 3 倍于直连距离的之字路，只看直连会把完美的陆路修复误判成
     * 「绕太远」而放开水面，桥就又会冒出来。
     *
     * @param direct         起点到终点的直线距离
     * @param originalLength 待修复走廊的现有长度
     * @param factor         landDetourFactor
     */
    static double landDetourCap(double direct, double originalLength, double factor) {
        return Math.max(Math.max(direct, 8.0D) * factor, originalLength * 1.05D);
    }

    /** 0.9.011：陆路闸门判定计数（只读观测）。 */
    static final java.util.concurrent.atomic.AtomicLong WATER_GATE_SEEN =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong WATER_GATE_LAND =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong WATER_GATE_CROSS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 闸门第一趟（湿列禁行）累计耗时。它是这版唯一可能让修复变贵的地方，必须看得见。 */
    static final java.util.concurrent.atomic.AtomicLong WATER_GATE_PASS1_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 0.9.011：陆路闸门判定日志。节流（前 40 次 + 之后每 200 次一条）。
     * 带累计 land / cross 计数（一局就能看出「桥到底还建不建」）与
     * 第一趟累计耗时 {@code pass1Ms}（判断这版是否让道路修复变慢的硬指标）。
     */
    static void recordWaterGateDiag(String verdict, double landLength, double direct,
                                            double factor, long gateNanos) {
        if ("land".equals(verdict)) {
            WATER_GATE_LAND.incrementAndGet();
        } else {
            WATER_GATE_CROSS.incrementAndGet();
        }
        long pass1Nanos = WATER_GATE_PASS1_NANOS.addAndGet(Math.max(0L, gateNanos));
        long seen = WATER_GATE_SEEN.incrementAndGet();
        if (seen <= 40 || seen % 200 == 0) {
            LOGGER.info("[ROAD-WATER-GATE] #{} verdict={} landLen={} direct={} factor={} "
                            // 0.9.049（D-16）：这两个数**是同一个量**（同一个 gateNanos），
                // 一个单次、一个累计。原来叫 gateMs/pass1Ms 会让人以为"闸门额外开销"被分开了，
                // 实际从来没有——闸门的开销就是这一趟 pass1，没有别的部分。改成自解释的名字。
                + "land={} cross={} pass1MsThisCall={} pass1MsTotal={}",
                    seen, verdict,
                    landLength < 0.0D ? "n/a" : String.format("%.1f", landLength),
                    String.format("%.1f", direct),
                    String.format("%.2f", factor),
                    WATER_GATE_LAND.get(), WATER_GATE_CROSS.get(),
                    Math.max(0L, gateNanos) / 1_000_000L, pass1Nanos / 1_000_000L);
        }
    }

    /** 0.9.009：临河距离惩罚。d<=0（在水里）加满；d<=radius 线性衰减；否则 0。 */
    static double riverProximityCost(int distanceBlocks, double penalty, int radius) {
        if (penalty <= 0.0D || radius <= 0) return 0.0D;
        if (distanceBlocks <= 0) return penalty;
        if (distanceBlocks > radius) return 0.0D;
        return penalty * (radius - distanceBlocks) / (double) radius;
    }

    /**
     * 0.9.009：窗口内「到最近湿列」的距离场（两遍 chamfer，单位=网格步）。
     * 湿列来自已采样的 {@code samples}（worldSurfaceWg > oceanFloorWg），不需要任何额外世界查询。
     * 网格太大（> 2M 格）时返回 null ⇒ 退化为"无临河惩罚"，绝不为了这个惩罚把内存打爆。
     */
    static int[] buildRiverDistanceField(Bounds bounds, int step,
                                                 Map<Long, AccurateHeightSample> samples) {
        if (bounds == null || samples == null || samples.isEmpty()) return null;
        int gridW = (bounds.maxX() - bounds.minX()) / step + 1;
        int gridH = (bounds.maxZ() - bounds.minZ()) / step + 1;
        if (gridW <= 0 || gridH <= 0 || (long) gridW * gridH > 2_000_000L) return null;
        int inf = 1 << 20;
        int[] dist = new int[gridW * gridH];
        java.util.Arrays.fill(dist, inf);
        for (Map.Entry<Long, AccurateHeightSample> entry : samples.entrySet()) {
            if (!isWetColumn(entry.getValue())) continue;
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            int gx = (x - bounds.minX()) / step;
            int gz = (z - bounds.minZ()) / step;
            if (gx < 0 || gz < 0 || gx >= gridW || gz >= gridH) continue;
            dist[gx + gz * gridW] = 0;
        }
        for (int z = 0; z < gridH; z++) {
            for (int x = 0; x < gridW; x++) {
                int i = x + z * gridW;
                if (dist[i] == 0) continue;
                int best = dist[i];
                if (x > 0) best = Math.min(best, dist[i - 1] + 1);
                if (z > 0) best = Math.min(best, dist[i - gridW] + 1);
                dist[i] = best;
            }
        }
        for (int z = gridH - 1; z >= 0; z--) {
            for (int x = gridW - 1; x >= 0; x--) {
                int i = x + z * gridW;
                if (dist[i] == 0) continue;
                int best = dist[i];
                if (x + 1 < gridW) best = Math.min(best, dist[i + 1] + 1);
                if (z + 1 < gridH) best = Math.min(best, dist[i + gridW] + 1);
                dist[i] = best;
            }
        }
        return dist;
    }

    /** 0.9.009：查距离场，返回格数（步数 × step）；越界或未建场返回 Integer.MAX_VALUE（=无惩罚）。 */
    static int riverDistanceBlocks(int[] field, int gridW, Bounds bounds, int step, int x, int z) {
        if (field == null || gridW <= 0) return Integer.MAX_VALUE;
        int gx = (x - bounds.minX()) / step;
        int gz = (z - bounds.minZ()) / step;
        if (gx < 0 || gz < 0 || gx >= gridW) return Integer.MAX_VALUE;
        int index = gx + gz * gridW;
        if (index < 0 || index >= field.length) return Integer.MAX_VALUE;
        int steps = field[index];
        return steps >= (1 << 20) ? Integer.MAX_VALUE : steps * step;
    }
}
