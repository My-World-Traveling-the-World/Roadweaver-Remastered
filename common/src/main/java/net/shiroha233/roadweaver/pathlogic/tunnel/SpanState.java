/* 文件职责：隧道 span 的状态与决策类型 —— SpanState（每段的状态容器）与 Decision（未决/批准/拒绝）。 */
package net.shiroha233.roadweaver.pathlogic.tunnel;

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
import static net.shiroha233.roadweaver.pathlogic.tunnel.PlannedSectionBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.PortalSafetyRules.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.ScenicDomeBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder.*;


/**
 * span 决策缓存。
 *
 * <h2>它记住什么</h2>
 * <p>每一段（span）在本次施工里是否已经判过、结论是什么（{@code Decision}：
 * 未决 / 已批准 / 已拒绝），以及这一段规划了哪些灯笼位置。</p>
 *
 * <h2>★ 为什么必须缓存决策</h2>
 * <p>同一段隧道会被多个 chunk 分别处理。如果每个 chunk 都重新判一次，
 * 除了浪费，更严重的是<b>两次判定可能给出不同结论</b>（地形采样有缓存差异），
 * 于是同一段路在一个 chunk 建、在另一个 chunk 不建 ⇒ 断洞。
 * 缓存把「结论」钉死成一次。</p>
 *
 * <h2>★ 为什么文件名是 SpanState 而不是 SpanStateStore</h2>
 * <p>本文件装的是<b>两个包私有类型</b>：{@link SpanState}（每段的状态容器）与
 * {@code Decision}（未决/批准/拒绝）。二者都从 1524 行的 {@code RoadTunnelBuilder}
 * 里原样切出，外部代码引用的是内层名 {@code SpanState}，不是文件名。
 * 文件名曾误写成 {@code SpanStateStore}（那次误命名导致「零引用扫描」永远扫不到本文件，
 * 差点被当成死代码删掉），2026-11-13 已改正。</p>
 *
 * <p>字段与判据一字未改（含缩进）。</p>
 */
enum Decision { UNKNOWN, APPROVED, REJECTED }

final class SpanState {
        final Object lock = new Object();
        Decision decision = Decision.UNKNOWN;
        final Set<Long> plannedLanterns = new HashSet<>();
        boolean lanternsPrepared;
        /** 0.9.064：洞口段长判定 —— 每个 span 只算一次、只打一行（同一 span 的其它站复用）。 */
        boolean portalResolved;
        volatile PortalSplit portalSplit;

        void preparePortal(WorldGenLevel world, RoadData road, List<BlockPos> centers,
                                   int[] profile, Span span) {
            if (portalResolved) return;
            portalResolved = true;
            portalSplit = announcePortalSplit(resolvePortalSplit(world, road, centers, profile, span));
        }

        void prepareLanterns(RoadData road, List<BlockPos> centers, int[] profile, Span span) {
            if (lanternsPrepared) return;
            lanternsPrepared = true;
            if (road == null || centers == null || profile == null || span == null
                    || road.roadSegmentList().size() != centers.size()) return;
            for (int index = span.start(); index <= span.end(); index++) {
                if ((index - span.start()) % LANTERN_INTERVAL != 0
                        || isInSpan(road.spans(), centers, index, SpanType.TUNNEL_JUNCTION)) continue;
                PlannedSection planned = plannedSection(
                        road.roadSegmentList().get(index), index, centers, profile, span.start());
                if (planned != null && planned.lantern() != null) {
                    plannedLanterns.add(planned.lantern().above(clearHeight()).asLong());
                }
            }
        }
    }
