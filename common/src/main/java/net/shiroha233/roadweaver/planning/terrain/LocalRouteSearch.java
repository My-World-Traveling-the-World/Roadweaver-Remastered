/* 文件职责：局部窗口内的 A* 重路由 —— 只在窗口里找新走线，不管判据与预算。 */
package net.shiroha233.roadweaver.planning.terrain;

import static net.shiroha233.roadweaver.planning.terrain.Bounds.horizontalDistanceSquared;
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

// 仍留在 AccurateGradeRepairPlanner 里的常量、代价函数与工具（包内可见）。
import static net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner.*;
import static net.shiroha233.roadweaver.planning.terrain.Bounds.key;
import static net.shiroha233.roadweaver.planning.terrain.Bounds.horizontalDistanceSquared;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.planning.terrain.StructureRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.SearchBudgetPolicy.*;
import static net.shiroha233.roadweaver.planning.terrain.RepairAcceptance.*;
import static net.shiroha233.roadweaver.planning.terrain.WaterCostModel.*;

/**
 * 局部窗口 A* 重路由。
 *
 * <h2>它做什么</h2>
 * <p>判出「这一段有问题」之后，不能整条路重算 —— 只在这一小段窗口里重新找一条
 * 更好的走线，再把窗口拼回去。窗口之外的站点一个都不动。</p>
 *
 * <h2>为什么有 8 个 {@code findLocalRoute} 重载</h2>
 * <p>它们不是随手堆的：每一个多一层约束（是否允许隧道、是否禁行湿列、
 * 预算是多少、是否走陆路闸门）。<b>重载链是逐层加约束的</b>，
 * 最终都落到同一个 {@code searchLocalRoute} —— 改的时候要顺着链看，
 * 只改最内层会漏掉外层传进来的约束。</p>
 *
 * <h2>★ 与判定层的分工</h2>
 * <p>「这条新走线能不能接受」<b>不在这里</b> —— 那是 {@code isAccepted} /
 * {@code isLakeRepairAccepted} / {@code isRiverRepairAccepted} 的事。
 * 本类只负责「找一条几何上可行的路」，接受与否由调用方判。
 * 这条分工是刻意的：判据散落在外面才能各自加单测。</p>
 *
 * <p>本类是从 3192 行的 {@code AccurateGradeRepairPlanner} 里<b>纯搬迁</b>出来的，
 * 代价函数、方向约束与预算一字未改（含缩进）。</p>
 */
final class LocalRouteSearch {
    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config) {
        return findLocalRoute(original, sampler, config, SEARCH_MARGIN_BLOCKS, EdgeObstacle.NONE,
                Math.max(1, config.effectiveAStarStep()), false, 0, null, Integer.MIN_VALUE);
    }

    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, EdgeObstacle.NONE,
                Math.max(1, config.effectiveAStarStep()), false, 0, null, Integer.MIN_VALUE);
    }

    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle,
                Math.max(1, config.effectiveAStarStep()), false, 0, null, Integer.MIN_VALUE);
    }

    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step, false, 0,
                null, Integer.MIN_VALUE);
    }

    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, null, Integer.MIN_VALUE);
    }

    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, Integer.MIN_VALUE);
    }

    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, tunnelFloorY, null);
    }

    /** Local 3D route search bounded by a circular conflict-centered radius. */
    static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY,
                                                  BlockPos conflictCenter) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, tunnelFloorY, conflictCenter, false);
    }

    /**
     * 0.8.326：{@code localStructureDetour=true} 时给隧道搜索用"普通搜索预算"
     * （{@link #repairWindowStateLimit()}，默认 9 万，可配置）而不是 24 万的
     * 放大上限。真机 20260909 16.15 存档 {@code (288,-256)->(880,208)} 的局部修复
     * 重生成阶段，三次结构绕行的 tunnel 搜索各花 20~29 秒且全部 {@code no_path}，
     * 白烧约 74 秒/局（该阶段总耗时 91 秒）。
     */
    /**
     * 0.9.050（D-10）：走廊附近有没有"湿列"。
     *
     * <p>只有为真时陆路闸门那一趟 A* 才有意义 —— 无水时它的结果与放开水面完全等价
     * （这是 0.9.011 自己写在注释里的论断）。逐站采样走 {@code sampler} 缓存，
     * 代价 O(站数)，远小于一整趟 A*（单次预算上限 20,000 ms）。</p>
     */
    static boolean corridorTouchesWater(List<BlockPos> original, AccurateHeightSampler sampler) {
        if (original == null || original.size() < 2 || sampler == null) return true;   // 判不了一律跑，宁慢勿错
        List<BlockPos> requests = new ArrayList<>(original.size());
        for (BlockPos pos : original) {
            if (pos != null) requests.add(pos);
        }
        if (requests.isEmpty()) return true;
        Map<Long, AccurateHeightSample> samples = sampler.samplePositions(requests);
        for (BlockPos pos : requests) {
            if (isWetColumn(samples.get(AccurateHeightSample.key(pos.getX(), pos.getZ())))) return true;
        }
        return false;
    }
static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY,
                                                  BlockPos conflictCenter,
                                                  boolean localStructureDetour) {
        // 0.9.011：陆路闸门。先只走陆路（湿列整列禁行）；只要绕行长度不超过直连距离的
        // landDetourFactor 倍，就直接采用它 —— 这就是「河边走没事，只有实在绕行太远才架桥」。
        // 没有水的时候这一趟的结果与放开水面完全相同，所以不产生额外的搜索代价。
        double gateFactor = 1.6D;
        try {
            gateFactor = Math.max(0.0D, Math.min(10.0D,
                    ConfigService.get().planning().landDetourFactor()));
        } catch (Throwable ignored) { }
        if (gateFactor > 0.0D && corridorTouchesWater(original, sampler)) {
            // 0.9.050（D-10）：只在走廊附近确实有水时才跑这一趟。
            // 上面那段注释自己写着「没有水的时候这一趟的结果与放开水面完全相同，所以不产生
            // 额外的搜索代价」—— 但代码里是无条件跑的，等于每个干窗口白跑一整趟 A*。
            // 真机证据：一局 24 次判定累计 pass1Ms = 116,822 ms，其中绝大多数窗口本来就无水。
            long gateStartedAt = System.nanoTime();
            LAST_SEARCH_BUDGET_EXHAUSTED.set(false);
            List<BlockPos> land = searchLocalRoute(original, sampler, config, searchMarginBlocks,
                    obstacle, step, allowTunnel, structureClearance, conflictBox, tunnelFloorY,
                    conflictCenter, localStructureDetour, true);
            double direct = horizontalDistance(original.get(0), original.get(original.size() - 1));
            // 基准取两支的较松者，缺一不可：
            //  ① 相对直连距离 —— 「绕行」的直观含义，direct 给 8 格下限防退化窗口；
            //  ② 不比原走廊更长（×1.05 容差）—— findLocalRoute 是**局部修复**，原走廊本身
            //     可能就是之字形（长度约 3× 直连）。只看直连会把「完美的陆路修复」误判成
            //     「绕太远」，于是又放开水面、桥照样冒出来，这次改动就白做了。
            double landCap = landDetourCap(direct, routeLength(original), gateFactor);
            long gateNanos = System.nanoTime() - gateStartedAt;
            String verdict;
            double landLength = -1.0D;
            if (land == null) {
                if (Boolean.TRUE.equals(LAST_SEARCH_BUDGET_EXHAUSTED.get())) {
                    // 0.9.069（D-11a）：陆路是「没搜完」（预算耗尽），不是「真不通」。
                    // 放开水域会 fail-open 到架桥（慢机/预算小的时候尤其爱乱架桥）。
                    // 这里直接返回 null，让上层 keepCandidateOnNull 保留原走廊。
                    recordWaterGateDiag("budget-exhausted", -1.0D, direct, gateFactor, gateNanos);
                    return null;
                }
                verdict = "no-land-route";
            } else {
                landLength = routeLength(land);
// 0.9.054（D-15）：把竖向爬升折算进比较，两边用**同一个系数** ——
                // 爬升相近时结果恒等于原来的纯水平比较，只有「陆路要爬的崖明显多于原走廊」
                // 才会被拒。这样既堵住「爬 25 格崖换 4 格水平」的漏洞，又不改常态行为。
                double landMetric = landLength + routeClimb(land) * VERTICAL_EQUIV_BLOCKS;
                double landCapMetric = landCap + routeClimb(original) * VERTICAL_EQUIV_BLOCKS;
                if (landMetric <= landCapMetric) {
                    recordWaterGateDiag("land", landLength, direct, gateFactor, gateNanos);
                    return land;
                }
                verdict = "detour-too-long";
            }
            recordWaterGateDiag(verdict, landLength, direct, gateFactor, gateNanos);
        }
        return searchLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, tunnelFloorY, conflictCenter,
                localStructureDetour, false);
    }

    /** 0.9.011：单趟 A* 本体。{@code wetBlocked=true} 时湿列整列禁行，供陆路闸门使用。 */
    static List<BlockPos> searchLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY,
                                                  BlockPos conflictCenter,
                                                  boolean localStructureDetour,
                                                  boolean wetBlocked) {
        step = Math.max(1, step);
        LAST_LOCAL_SEARCH_DIAG.remove();
        BlockPos start = original.get(0);
        BlockPos goal = original.get(original.size() - 1);
        int radius = Math.max(step, searchMarginBlocks);
        Bounds bounds = conflictCenter == null
                ? Bounds.around(original, radius, step, conflictBox)
                : Bounds.aroundConflict(conflictCenter, radius, step, start, goal, conflictBox);
        Collection<BlockPos> requests = conflictCenter == null
                ? bounds.positions(step)
                : bounds.positionsWithinRadius(step, conflictCenter, radius);
        if (conflictCenter != null) {
            // The local detour must always have usable entry and exit samples.
            // They may lie just outside the circle after grid snapping, but
            // remain part of the original local corridor and must not make a
            // valid red-style bypass fail before A* starts.
            Map<Long, BlockPos> requestMap = new LinkedHashMap<>();
            for (BlockPos request : requests) {
                requestMap.put(key(request.getX(), request.getZ()), request);
            }
            if (bounds.contains(start.getX(), start.getZ())) {
                requestMap.put(key(start.getX(), start.getZ()), start);
            }
            if (bounds.contains(goal.getX(), goal.getZ())) {
                requestMap.put(key(goal.getX(), goal.getZ()), goal);
            }
            requests = requestMap.values();
        }
        if (requests.isEmpty()) return null;
        int expandedStateLimit = expandedStateLimit(requests.size(), allowTunnel, step, localStructureDetour);
        long searchStartedAt = System.nanoTime();
        // 0.9.001：状态数预算之外再加一条墙钟预算。只靠 expandedStateLimit 时，
        // 跨 90 格深谷这种窗口每次都把 90 000 展开跑满、空转 95 秒以上再返回无解
        // （真机 road_grade_search 卡顿主因，三波重试各跑一次）。两者谁先到谁生效。
        long searchDeadlineNanos = searchStartedAt + repairWindowBudgetNanos();
        if (wetBlocked) {
            // 0.9.014：陆路闸门这一趟只负责回答「陆路到底通不通」，不该跑满全预算。
            // 真机实测（低挡位一局）：24 次判定累计 pass1Ms=116,822，其中 12 次
            // verdict=no-land-route 全部把预算跑满才失败（最慢单次 20 s）；而那一局的
            // 最终道路几何与不设闸门时逐字节一致（两局 slice 计数器都是
            // bridge=348 tunnel=45）⇒ 这 117 秒是纯亏。
            // 砍到 1/4：失败的判定 4 倍快地放手；成功的那些 landLen 只有 100~300 格，
            // 本来就用不到预算的零头，路径结果不变。
            // 0.9.051（D-11）：原来这里各套了一个下限 Math.max(2_000, …) / Math.max(50_000_000L, …)，
            // 但它们**永远不可能生效**：
            //   · expandedStateLimit 的下限是 repairWindowStateLimit() 里的 20_000 ⇒ /4 = 5_000 > 2_000；
            //   · repairWindowBudgetNanos() 的下限是 1_000 ms = 1e9 ns ⇒ /4 = 2.5e8 ns > 5e7 ns。
            // 删掉两个死下限，真实有效下限写在这里：**5 000 个展开状态 / 250 ms**。
            expandedStateLimit = expandedStateLimit / 4;
            searchDeadlineNanos = searchStartedAt + repairWindowBudgetNanos() / 4L;
        }
        // 0.9.009 / 0.9.011：临河与过水代价参数（每搜索读一次配置，不在展开循环里读）
        double waterBase = 200.0D;
        double waterPerStation = 150.0D;
        double riverPenalty = 8.0D;
        int riverRadius = 5;
        double culvertPerStation = 2.0D;
        int narrowWaterMax = 20;
        double narrowWaterPer = 20.0D;
        double wideBase = 600.0D;
        double widePer = 80.0D;
        boolean waterGate = true;
        try {
            var plan = ConfigService.get().planning();
// 0.9.051（D-18）：这两个键（baseWaterPenalty / perStationWaterPenalty）在
            // **闸门开启（默认）时完全不参与计价** —— 它们只在 :1515 的 !waterGate 分支里
            // 被赋回 wideBase/widePer。也就是说配置界面里改它们，默认态下没有任何效果。
            waterBase = Math.max(0.0D, Math.min(100_000.0D, plan.baseWaterPenalty()));
            waterPerStation = Math.max(0.0D, Math.min(100_000.0D, plan.perStationWaterPenalty()));
            riverPenalty = Math.max(0.0D, Math.min(100_000.0D, plan.riverProximityPenalty()));
            riverRadius = Math.max(0, Math.min(32, plan.riverProximityRadius()));
            culvertPerStation = Math.max(0.0D, Math.min(100.0D, plan.culvertCostPerStation()));
            narrowWaterMax = Math.max(0, Math.min(256, plan.narrowWaterMaxWidth()));
            narrowWaterPer = Math.max(0.0D, Math.min(100_000.0D, plan.narrowWaterPerStation()));
            wideBase = Math.max(0.0D, Math.min(100_000.0D, plan.wideWaterBase()));
            widePer = Math.max(0.0D, Math.min(100_000.0D, plan.wideWaterPerStation()));
            waterGate = plan.landDetourFactor() > 0.0D;
        } catch (Throwable ignored) { }
        // 闸门关闭 ⇒ 完整回到 0.9.010 口径：宽水用 base/per，窄水档不启用。
        if (!waterGate) {
            wideBase = waterBase;
            widePer = waterPerStation;
            narrowWaterMax = 0;
        }
        int culvertMaxStations = 6;
        int culvertMaxWaterDepth = 2;
        try {
            var look = ConfigService.get().roadAppearance();
culvertMaxStations = Math.max(0, Math.min(16, look.culvertMaxStations()));
            // 0.9.052（D-13）：culvertEnabled=false 时**施工端不建涵洞**（Road.java:353 才读它），
            // 但寻路这一侧从来没读过这个开关 —— 于是 ≤6 站浅水仍按涵洞价（2/站）定价，
            // A* 认为"直接过很便宜"就选穿越，施工阶段却铺普通路面、或被 FinalRoadWaterValidator 打回。
            // 把档位宽度置 0 即彻底关掉涵洞档，价格自然落到窄水/宽水，选线与施工口径重新一致。
            if (!look.culvertEnabled()) {
                culvertMaxStations = 0;
            }
            culvertMaxWaterDepth = Math.max(1, Math.min(4, look.culvertMaxWaterDepth()));
        } catch (Throwable ignored) { }
        // R7（0.9.003，0.9.059 收紧）：长度上界可证明剪枝。
        // 旧界 max(原走廊长+200, 原走廊长×3.0) 比真机那条 3.63 倍绕行还松，等于没剪；
        // 现在换成 max(直连×1.6, 原走廊长×1.05)（见 lengthCapFor 的等价性/剪枝安全性说明）。
        // 任何经过某节点的解，其**返回路径长度**下界为 g_len + hypot(到目标) - 两端网格吸附偏差，
        // 一旦该下界已超过上界，这个解必然被拒 ⇒ 剪掉不改变"≤ cap 的解"的结果集。
        double chord = chordLength(original);
        double originalLength = routeLength(original);
        // 0.9.062（①）：factor / cap 要等窗口网格采完之后才能定 —— steep 判据要用那张网格的高度。
        double lengthBound;
        // 两端偏差各 <= step*sqrt(2)，取 4*step (> 2.83*step) 做安全余量。
        double lengthSlack = 4.0D * step;
        boolean lengthBoundPrune = lengthBoundPruneEnabled();
        int prunedByLength = 0;
        Map<Long, AccurateHeightSample> samples = sampler.samplePositions(requests);
        Map<Long, Integer> heights = new HashMap<>(samples.size() * 2);
        for (BlockPos request : requests) {
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(request.getX(), request.getZ()));
            if (sample != null) {
                heights.put(key(request.getX(), request.getZ()), sampler.surfaceHeight(sample));
            }
        }
        // 0.9.062（①）：策略感知的长度界。判据只读上面这张**已经采过**的窗口网格（heights map），
        // 不发起任何新的地形查询；弦被圆心窗口截断时采不到足够的点 ⇒ steep=false ⇒ 仍是 1.6。
        boolean steepChord = steepLengthCapEnabled()
                && chordIsSteep(chordProfile(heights, start, goal, bounds.minX(), bounds.minZ(), step),
                chordSampleSpacing(start, goal, step));
        double lengthFactor = detourFactorFor(steepChord);
        lengthBound = lengthCapFor(chord, originalLength, steepChord);
        // 0.9.009：窗口内一次性建「到最近湿列」的距离场（两遍 chamfer，O(窗口面积)），
        // 之后每次邻居展开只做一次数组读 —— 不做逐邻居查河，expanded 不会因此上涨。
        int[] riverDistanceField = riverPenalty > 0.0D && riverRadius > 0
                ? buildRiverDistanceField(bounds, step, samples) : null;
        int riverDistanceGridW = riverDistanceField == null ? 0
                : (bounds.maxX() - bounds.minX()) / step + 1;

        int startX = nearestGrid(start.getX(), bounds.minX(), step);
        int startZ = nearestGrid(start.getZ(), bounds.minZ(), step);
        int goalX = nearestGrid(goal.getX(), bounds.minX(), step);
        int goalZ = nearestGrid(goal.getZ(), bounds.minZ(), step);
        Integer startY = heights.get(key(startX, startZ));
        Integer goalY = heights.get(key(goalX, goalZ));
        if (startY == null || goalY == null) return null;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<StateKey, Double> best = new HashMap<>();
        Node first = new Node(startX, startY, startZ, -1, 0.0D, 0.0D, 0,
                heuristic(startX, startZ, goalX, goalZ), null);
        open.add(first);
        best.put(first.key(), 0.0D);
        int expanded = 0;
        while (!open.isEmpty() && expanded < expandedStateLimit
                && System.nanoTime() < searchDeadlineNanos) {
            Node current = open.poll();
            Double known = best.get(current.key());
            if (known == null || current.cost() > known + 0.0001D) continue;
            if (current.x() == goalX && current.z() == goalZ) {
                List<BlockPos> route = reconstruct(current);
                route.set(0, start);
                route.set(route.size() - 1, goal);
                recordLocalSearchDiag(expanded, expandedStateLimit, allowTunnel,
                        localStructureDetour, searchStartedAt, true, false, prunedByLength);
                // 0.9.059（③）：长度上界诊断（cap 与这条路的实际长度、弦长同框）。
                // 0.9.062（①）：steep/factor 是这一趟真正用的那两个数。
                recordLengthCapDiag(lengthBound, lengthFactor, steepChord, chord, originalLength,
                        routeLength(route), prunedByLength, expanded, true);
                return route;
            }
            expanded++;
            if ((expanded & 2047) == 0) {
                // 0.9.001：原循环体内完全没有中断检查 —— 建路 worker 被取消后这条 A*
                // 仍会跑满 90 000 次展开（真机 95 秒）。语义与同文件
                // prepareLocalStructureCircle 的处理一致：立刻转成 CancellationException。
                if (Thread.currentThread().isInterrupted()) {
                    recordLocalSearchDiag(expanded, expandedStateLimit, allowTunnel,
                            localStructureDetour, searchStartedAt, false, false, prunedByLength);
                    throw new java.util.concurrent.CancellationException(
                            "interrupted during local grade repair search");
                }
                InitialGenerationProgressTracker.advanceCurrentRoadTask(
                        240 + (int) Math.min(280L, expanded * 280L / expandedStateLimit),
                        "road_grade_search");
            }
            for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                int nextX = current.x() + DIRECTIONS[direction][0] * step;
                int nextZ = current.z() + DIRECTIONS[direction][1] * step;
                if (!bounds.contains(nextX, nextZ)) continue;
                if (conflictCenter != null
                        && horizontalDistanceSquared(nextX, nextZ, conflictCenter.getX(), conflictCenter.getZ())
                        > (long) radius * radius) continue;
                Integer terrainY = heights.get(key(nextX, nextZ));
                if (terrainY == null) continue;
                // 0.9.011：陆路闸门那一趟把湿列整列禁行。端点例外 —— 它们本来就在岸上，
                // 而且后面会被强制吸附到 startY / goalY。
                if (wetBlocked
                        && !(nextX == goalX && nextZ == goalZ)
                        && !(nextX == startX && nextZ == startZ)
                        && isWetColumn(samples.get(AccurateHeightSample.key(nextX, nextZ)))) {
                    continue;
                }
                int[] candidateYs = allowTunnel
                        ? tunnelHeights(terrainY, step, tunnelFloorY) : new int[]{terrainY};
                for (int nextY : candidateYs) {
                    if (nextX == goalX && nextZ == goalZ) nextY = goalY;
                    if (nextX == startX && nextZ == startZ) nextY = startY;
                double horizontal = Math.hypot(nextX - current.x(), nextZ - current.z());
                int rise = Math.abs(nextY - current.y());
                if (rise > Math.floor(horizontal)) continue;
                // R7：长度上界剪枝放在最贵的 edgeIntersectsProtected / obstacle.blocked 之前，
                // 只用一个已算好的启发值做比较。能到这里说明垂直与网格都已通过。
                double remaining = heuristic(nextX, nextZ, goalX, goalZ);
                double nextLength = current.length() + horizontal;
                if (lengthBoundPrune && nextLength + remaining - lengthSlack > lengthBound) {
                    prunedByLength++;
                    continue;
                }
                BlockPos currentPos = new BlockPos(current.x(), current.y(), current.z());
                BlockPos nextPos = new BlockPos(nextX, nextY, nextZ);
                if (edgeIntersectsProtected(currentPos, nextPos,
                        Math.max(0, structureClearance))
                        || obstacle.blocked(currentPos, nextPos)) continue;

                double grade = rise / Math.max(1.0D, horizontal);
                AccurateHeightSample nextSample = samples.get(AccurateHeightSample.key(nextX, nextZ));
                boolean wet = isWetColumn(nextSample);
                // (b) 过河代价按「连续水站数」递增，替换原来的固定 1200：3 步 650 / 50 步 7700。
                int nextWetRun = wet ? current.wetRun() + 1 : 0;
                // 过河总价 T(run)：run <= culvertMaxStations 且水深 <= culvertMaxWaterDepth 时
                // = run × culvertCostPerStation（3 站 6、6 站 12）；否则 = base + per × run
                // （7 站 1250、10 站 1700、50 站 7700）。A* 是增量代价，所以按 T 的「边际」收费：
                // 第 k 个湿步收 T(k) - T(k-1) ⇒ 跨过涵洞上限那一步会一次性补上断崖差额。
                int wetDepth = wet
                        ? Math.max(0, nextSample.worldSurfaceWg() - nextSample.oceanFloorWg())
                        : 0;
                double waterPenalty = 0.0D;
                if (wet) {
                    double total = waterCrossingCost(nextWetRun, wetDepth, wideBase, widePer,
                            culvertPerStation, culvertMaxStations, culvertMaxWaterDepth,
                            narrowWaterMax, narrowWaterPer, step);
                    double previous = nextWetRun > 1
                            ? waterCrossingCost(nextWetRun - 1, wetDepth, wideBase, widePer,
                                    culvertPerStation, culvertMaxStations, culvertMaxWaterDepth,
                                    narrowWaterMax, narrowWaterPer, step)
                            : 0.0D;
                    waterPenalty = Math.max(0.0D, total - previous);
                }
                // (a) 临河距离惩罚：距最近湿列越近越贵，在水里加满；O(1) 查距离场。
                double riverCost = riverProximityCost(
                        riverDistanceBlocks(riverDistanceField, riverDistanceGridW, bounds, step,
                                nextX, nextZ),
                        riverPenalty, riverRadius);
                double turnPenalty = current.direction() < 0 || current.direction() == direction ? 0.0D : 4.0D;
                double tunnelPenalty = Math.max(0, terrainY - nextY) * 2.5D;
                double nextCost = current.cost() + horizontal + rise * 8.0D + tunnelPenalty
                        + grade * grade * 90.0D + waterPenalty + riverCost + turnPenalty;
                StateKey state = new StateKey(nextX, nextY, nextZ, direction);
                Double old = best.get(state);
                if (old != null && old <= nextCost) continue;
                Node next = new Node(nextX, nextY, nextZ, direction, nextCost, nextLength,
                        nextWetRun, nextCost + remaining, current);
                best.put(state, nextCost);
                open.add(next);
                }
            }
        }
        // 退出 A* 的三条路径：open 空（确实到不了）、状态数预算耗尽、墙钟预算耗尽。
        // 后两者语义完全相同（一律返回 null，走既有「无解」兜底），只在诊断串里区分，
        // 便于真机验收「预算真的兜住了」而不是又跑满一遍。
        boolean budgetExhausted = expanded < expandedStateLimit && !open.isEmpty();
        LAST_SEARCH_BUDGET_EXHAUSTED.set(budgetExhausted);
        recordLocalSearchDiag(expanded, expandedStateLimit, allowTunnel,
                localStructureDetour, searchStartedAt, false, budgetExhausted, prunedByLength);
        recordLengthCapDiag(lengthBound, lengthFactor, steepChord, chord, originalLength, -1.0D,
                prunedByLength, expanded, false);
        return null;
    }
}
