/* 文件职责：道路生成队列的刷新与轮询 —— 谁先做、做哪些、什么时候暂停。 */
package net.shiroha233.roadweaver.generation;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.config.PathFeatureConfig;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainTileCache;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.ExistingRoadAttachment;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.ConnectionFallbackService;
import net.shiroha233.roadweaver.planning.ConnectionTopologyReconciler;
import net.shiroha233.roadweaver.planning.ExistingRoadEndpointReuse;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static net.shiroha233.roadweaver.generation.RoadTaskExecutor.*;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.generation.RoadGenerationService.*;

/**
 * 队列刷新与轮询。
 *
 * <h2>它在决定什么</h2>
 * <p>同一时刻可能有几十条待生成的路。{@code refreshQueue} 决定这一轮**做哪些**，
 * {@code pollNearest} 决定**先做哪条**（离玩家近的优先，玩家能看到进度）。</p>
 *
 * <h2>★ 暂停要能豁免玩家自己的路</h2>
 * <p>全局暂停（切维度、预热未完成、内存压力）时，如果一条都不做，玩家点了「修路」
 * 会以为坏了。{@code isPauseExemptForPlayerRoute} 就是那条豁免口子 ——
 * **只放行玩家主动要的那条**，不影响后台批量。</p>
 *
 * <p>本类是从 {@code RoadGenerationService} 里<b>纯搬迁</b>出来的，
 * 排队规则与优先级判据一字未改（含缩进）。</p>
 */
public final class RoadQueueOps {
    public static void refreshQueue(ServerLevel level) {
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> list = provider.getStructureConnections(level);
        if (list == null) return;

        ConnectionTopologyReconciler.Result topology = reconcileConnections(level, list);
        if (!topology.removed().isEmpty()) {
            provider.setStructureConnections(level, topology.connections());
            Set<PlannedPathKey> removedKeys = new HashSet<>();
            for (StructureConnection removed : topology.removed()) {
                PlannedPathKey key = PlannedPathKey.of(removed);
                removedKeys.add(key);
                PROCESSED.remove(key);
                MapPatchService.publishConnectionRemoval(level, removed);
                LOGGER.info("RoadWeaver removed redundant pending connection already covered by completed network: {} -> {} status={}",
                        removed.from(), removed.to(), removed.status());
            }
            QUEUE.removeIf(connection -> removedKeys.contains(PlannedPathKey.of(connection)));
            list = topology.connections();
        }

        ConcurrentLinkedQueue<StructureConnection> q = QUEUE;
        ConcurrentHashMap<PlannedPathKey, Boolean> proc = PROCESSED;

        for (StructureConnection c : list) {
            if (c.status() != ConnectionStatus.PLANNED && c.status() != ConnectionStatus.GENERATING) continue;
            if (RoadPlanningReadinessIndex.shouldReserveForCoordinator(level, c)) {
                continue;
            }
            if (c.status() == ConnectionStatus.PLANNED
                    && IdleRoadGenerationService.shouldReserveForIdle(level, c)) {
                continue;
            }
            if (c.status() == ConnectionStatus.GENERATING
                    && IdleRoadGenerationService.isManagedByIdle(level, c)) {
                continue;
            }
            PlannedPathKey key = PlannedPathKey.of(c);
            if (proc.putIfAbsent(key, Boolean.TRUE) == null) {
                q.add(c);
            }
        }
    }

    public static StructureConnection pollNearest(ConcurrentLinkedQueue<StructureConnection> q,
                                                   List<ServerPlayer> players,
                                                   ServerLevel level) {
        if (q.isEmpty()) return null;

        // ★ 任务 C（2026-09-26）：**手绘路线连接永远先出队**
        //   （用户要求「手动道路优先级最高」）。
        //
        //   为什么必须加这一级：队列原本只按「离玩家近」排序，
        //   而自动规划可能一次性入队几十条连接 —— 手绘路线虽然刚提交、
        //   也离玩家近，但仍可能排在若干更近的自动连接后面。
        //   玩家点了施工在盯着屏幕，必须让它插到最前。
        //
        //   用 isPlayerRoute 判定（登记表），O(1) 查表；异常时按非手绘处理，
        //   绝不让优先级判定影响主流程。
        if (level != null) {
            for (StructureConnection e : q) {
                if (e != null && isPlayerRouteSafe(level, e)) {
                    if (q.remove(e)) return e;
                }
            }
        }

        if (players == null || players.isEmpty()) return q.poll();

        StructureConnection best = null;
        long bestDist = Long.MAX_VALUE;
        for (StructureConnection e : q) {
            long d = playerDistance2(e, players);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best != null && q.remove(best)) return best;
        return q.poll();
    }

    public static boolean isPauseExemptForPlayerRoute() {
        try {
            var cfg = ConfigService.get();
            if (cfg == null || cfg.performance() == null
                    || !cfg.performance().playerRoutePriorityEnabled()) {
                return false;
            }
            String spec = cfg.performance().playerRoutePriorityExemptPauseReasons();
            if (spec == null || spec.isBlank()) {
                return false;
            }
            var kind = RuntimeLoadPolicy.currentPauseKind();
            if (kind == RuntimeLoadPolicy.PauseKind.NONE) {
                return true;   // 没暂停，无需豁免
            }
            String wanted = kind.name();
            for (String part : spec.split(",")) {
                if (part != null && part.trim().equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            // 配置读不到就按"不豁免"（保守：退回让路规则）
            return false;
        }
    }
}
