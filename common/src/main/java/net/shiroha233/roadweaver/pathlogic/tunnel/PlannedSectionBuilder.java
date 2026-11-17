/* 文件职责：计划小节装配 —— 把隧道切成 chunk 能独立处理的小节并算横向偏移。 */
package net.shiroha233.roadweaver.pathlogic.tunnel;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.tags.FluidTags;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadHeightInterpolator;
import net.shiroha233.roadweaver.decoration.ScenicGlassShell;
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService;
import net.shiroha233.roadweaver.pathlogic.construction.SegmentPaver;
import net.shiroha233.roadweaver.pathlogic.core.Road;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.PortalSafetyRules.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.ScenicDomeBuilder.*;

/**
 * 计划小节装配。
 *
 * <h2>为什么要分「小节」</h2>
 * <p>施工按 chunk 分发，一个隧道可能横跨几十个 chunk。装配时要把连续站点切成
 * <b>能被单个 chunk 完整处理</b>的小节，否则会出现「这个 chunk 只铺一半就返回 false」
 * ⇒ 邻接 chunk 也不管 ⇒ <b>断洞</b>。</p>
 *
 * <h2>★ columnAvailableWithoutLoading 的意义</h2>
 * <p><b>必须能在不加载区块的前提下判断这一列能不能用</b> —— 施工期在 chunk 生成
 * 线程上，主动 getChunk 会死锁。拿不到就当「不可用」，交给下一个 chunk 处理，
 * 绝不阻塞。</p>
 *
 * <p>本类是从 1521 行的 {@code RoadTunnelBuilder} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class PlannedSectionBuilder {
    public static boolean columnAvailableWithoutLoading(WorldGenLevel world, BlockPos position) {
        if (!(world instanceof ServerLevel level) || position == null) return true;
        return level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) != null;
    }

    public static PlannedSection plannedSection(RoadSegmentPlacement segment, int index,
                                         List<BlockPos> centers, int[] profile, int spanStart) {
        if (segment == null || segment.positions() == null || segment.positions().isEmpty()) return null;
        int[] heights = RoadHeightInterpolator.batchInterpolate(segment.positions(), index, centers, profile);
        if (heights.length != segment.positions().size()) return null;
        List<BlockPos> road = new ArrayList<>();
        for (int i = 0; i < heights.length; i++) road.add(new BlockPos(segment.positions().get(i).getX(), heights[i], segment.positions().get(i).getZ()));
        BlockPos before = centers.get(Math.max(0, index - 1));
        BlockPos after = centers.get(Math.min(centers.size() - 1, index + 1));
        int dx = after.getX() - before.getX();
        int dz = after.getZ() - before.getZ();
        int nx = -Integer.signum(dz);
        int nz = Integer.signum(dx);
        if (nx == 0 && nz == 0) return null;
        // The lateral offsets below step by whole cells, so the projection normal must move exactly one
        // projection unit per cell. On a diagonal route the true normal is (-1,1)/sqrt2, and stepping a
        // single cell along it moves *two* projection units: the wall column landed at p=+-5 while the
        // roadway spans p in [-1,+1], leaving an unexcavated ridge at p=+-2/+-4 with the stone lining
        // buried inside the rock - a bore that looks three wide instead of five and changes width at every
        // turn. Stepping along both axes on a diagonal keeps every step at one projection unit.
        boolean diagonal = dx != 0 && dz != 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (BlockPos pos : road) {
            int p = pos.getX() * nx + pos.getZ() * nz;
            min = Math.min(min, p); max = Math.max(max, p);
        }
        Map<Long, BlockPos> interior = unique(road);
        Map<Long, BlockPos> walls = new LinkedHashMap<>();
        for (BlockPos pos : road) {
            int p = pos.getX() * nx + pos.getZ() * nz;
            if (p == min) lateral(interior, walls, pos, -1, nx, nz, diagonal);
            if (p == max) lateral(interior, walls, pos, 1, nx, nz, diagonal);
        }
        Map<Long, BlockPos> shell = unique(interior.values());
        shell.putAll(walls);
        return new PlannedSection(List.copyOf(road), List.copyOf(interior.values()), List.copyOf(walls.values()),
                List.copyOf(shell.values()), road.get(road.size() / 2));
    }

    /** 斜向截面法线须映射到两轴相邻列，避免单格步进跨掉侧壁。
 *
     * Adds the clearance and wall cells one lateral step out from a roadway column.
     *
     * <p>Axis-aligned routes step once along the normal. Diagonal routes step along both axes for the same
     * lateral step, because a diagonal normal cannot be expressed as a single cell and stepping one cell
     * along it would move two projection units (see the comment in {@link #plannedSection}).
     */
    public static void lateral(Map<Long, BlockPos> interior, Map<Long, BlockPos> walls,
                                BlockPos pos, int sign, int nx, int nz, boolean diagonal) {
        if (diagonal) {
            add(interior, pos.offset(sign * SIDE_CLEARANCE * nx, 0, 0));
            add(interior, pos.offset(0, 0, sign * SIDE_CLEARANCE * nz));
            add(walls, pos.offset(sign * WALL_OFFSET * nx, 0, 0));
            add(walls, pos.offset(0, 0, sign * WALL_OFFSET * nz));
            return;
        }
        add(interior, pos.offset(sign * SIDE_CLEARANCE * nx, 0, sign * SIDE_CLEARANCE * nz));
        add(walls, pos.offset(sign * WALL_OFFSET * nx, 0, sign * WALL_OFFSET * nz));
    }

    /**
     * 0.9.065：这一站所在的 TUNNEL span 站区间（闭区间 {@code [first, last]}）；
     * 不是隧道站返回 {@code null}。给 [TUNNEL-CLEAR] 台账按段记账用 —— 只读查询，不改任何判定。
     */
    public static int[] tunnelScope(RoadData road, List<BlockPos> centers, int segmentIndex) {
        if (road == null || centers == null || centers.isEmpty()) return null;
        Span span = findSpan(road.spans(), centers, segmentIndex);
        if (span == null) return null;
        return new int[] { Math.min(span.start(), span.end()), Math.max(span.start(), span.end()) };
    }

    public static Span findSpan(List<RoadSpan> spans, List<BlockPos> centers, int index) {
        if (spans == null) return null;
        for (RoadSpan candidate : spans) {
            if (candidate == null || candidate.type() != SpanType.TUNNEL) continue;
            int start = indexOf(centers, candidate.start());
            int end = indexOf(centers, candidate.end());
            if (start < 0 || end < 0) continue;
            if (start > end) { int swap = start; start = end; end = swap; }
            if (index >= start && index <= end) return new Span(start, end);
        }
        return null;
    }

    public static boolean isInSpan(List<RoadSpan> spans, List<BlockPos> centers, int index, SpanType type) {
        if (spans == null || type == null) return false;
        for (RoadSpan candidate : spans) {
            if (candidate == null || candidate.type() != type) continue;
            int start = indexOf(centers, candidate.start());
            int end = indexOf(centers, candidate.end());
            if (start < 0 || end < 0) continue;
            if (index >= Math.min(start, end) && index <= Math.max(start, end)) return true;
        }
        return false;
    }

    /**
     * 站索引反查：先精确 XZ，失配时退化为「最近的站」（0.9.080）。
     *
     * <p>为什么必须有兜底：`RoadSpan` 只存 BlockPos 不存站索引，而站序列会被
     * `ExistingRoadJunctionService.mergeRoads` / `Road.rebuild` 重建过，
     * 重建后同一逻辑位置的坐标可能漂 1~2 格。原来精确匹配 == 找不到 ==
     * `findSpan` 返回 null == <b>整段隧道一格都不挖</b>，而且全程静默。
     * 这与 0.9.036 修「桥共享丢整座桥」是同一个病根，这里用同一套兜底思路：
     * 允许 ≤{@value #STATION_XZ_FALLBACK_TOLERANCE} 格的漂移，超过就放弃（宁可丢不可错配）。</p>
     */
    public static int indexOf(List<BlockPos> centers, BlockPos target) {
        if (target == null || centers == null || centers.isEmpty()) return -1;
        long targetKey = xzKey(target);
        for (int i = 0; i < centers.size(); i++) {
            if (xzKey(centers.get(i)) == targetKey) return i;
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            BlockPos c = centers.get(i);
            if (c == null) continue;
            int d = Math.max(Math.abs(c.getX() - target.getX()), Math.abs(c.getZ() - target.getZ()));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        if (best >= 0 && bestDist <= STATION_XZ_FALLBACK_TOLERANCE) {
            if (STATION_FALLBACK_HITS.incrementAndGet() <= 8) {
                LOGGER.info("【隧道跨度端点已匹配最近站点】 " + "RoadWeaver tunnel span endpoint matched by nearest station: target={} nearest={} dxz={}",
                        target, centers.get(best), bestDist);
            }
            return best;
        }
        if (STATION_FALLBACK_MISSES.incrementAndGet() <= 8) {
            LOGGER.warn("【隧道跨度端点无法匹配，本跨度将整体跳过】 " + "RoadWeaver tunnel span endpoint unmatchable: target={} nearestDxz={} centers={} "
                    + "-> this tunnel span will be skipped entirely",
                    target, best < 0 ? -1 : bestDist, centers.size());
        }
        return -1;
    }

    public static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    public static Map<Long, BlockPos> unique(Iterable<BlockPos> positions) {
        Map<Long, BlockPos> result = new LinkedHashMap<>();
        for (BlockPos pos : positions) add(result, pos);
        return result;
    }

    public static void add(Map<Long, BlockPos> positions, BlockPos pos) {
        positions.putIfAbsent(pos.asLong(), pos);
    }

    public static SpanState spanState(net.minecraft.server.level.ServerLevel level, RoadData road,
            Span span, List<BlockPos> centers) {
        ConcurrentHashMap<SpanKey, SpanState> states;
        synchronized (SPAN_STATES) {
            states = SPAN_STATES.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
        }
        long start = centers.get(span.start()).asLong();
        long end = centers.get(span.end()).asLong();
        long first = Math.min(start, end);
        long second = Math.max(start, end);
        long ownerFirst = road != null && road.hasOwnerPair() ? Math.min(road.ownerA2dKey(), road.ownerB2dKey()) : 0L;
        long ownerSecond = road != null && road.hasOwnerPair() ? Math.max(road.ownerA2dKey(), road.ownerB2dKey()) : 0L;
        return states.computeIfAbsent(new SpanKey(first, second, ownerFirst, ownerSecond), ignored -> new SpanState());
    }
}
