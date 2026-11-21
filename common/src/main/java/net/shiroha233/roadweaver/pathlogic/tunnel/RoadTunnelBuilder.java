/* 文件职责：按已持久化的 TUNNEL span 保守开挖隧道 —— 不安全的一段就不动，绝不当地面路铺进实心地形。
 *
 * 「不安全就保持原样」是刻意的保守策略：把隧道段退化成地面铺装会造成
 * 实心地形里出现一条悬空的路面，比不建更糟。所以宁可不建。
 */
package net.shiroha233.roadweaver.pathlogic.tunnel;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
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

/** 持久隧道跨度逐截面预检，不安全截面不能默默按普通地表道路埋入岩层。
 *
 * Conservative tunnel construction for persisted TUNNEL spans. Each section
 * is checked before excavation; an unsafe section is left untouched instead
 * of being paved as an ordinary road inside solid terrain.
 */
public final class RoadTunnelBuilder {
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** 历史净空常量保留为默认值，实际建造仍读取冻结配置。 */
    static final int DEFAULT_CLEAR_HEIGHT = 5;
    /** 隧道净空与铺路清空使用同一冻结配置，避免设高后顶板与清空范围分离。
 *
     * Clear height of the bore, read from the frozen config instead of a constant.
     *
     * <p>It used to be hard-coded five, so raising "tunnel clear height" in the settings changed a number
     * that nothing read: the bore stayed five high while the road paver cleared to the configured height,
     * which left an air gap above the roof of any tunnel configured taller. Reading the same value the paver
     * reads is what makes the setting do something.
     */
    public static int clearHeight() {
        return Math.max(1, net.shiroha233.roadweaver.config.ConfigService.get()
                .roadAppearance().tunnelClearHeight());
    }

    /** 顶板在净空上方一格封口，避免将可步行净空算入实体屋顶。 */
    public static int roofOffset() { return clearHeight() + 1; }

    /** 覆土决定密封或开放口部，不能把地表薄层一律封成洞体。 */
    public static int minOverburden() { return roofOffset() + 1; }
    /** 顶板与开放水之间保留实岩层；海床高度须先扣顶板偏移再扣覆土，不能把水当岩石。
 *
     * Solid rock the profile must leave between the roof and the first block of open water.
     *
     * <p>Planning used to place an undersea tunnel's deck {@code safeClear + 1} below the ocean floor's
     * first water block, which put the roof exactly <em>in</em> that first water block: zero rock cover, a
     * stone-brick tube lying on the seabed with the sea on top of it. Two blocks is the smallest cover that
     * still reads as a tunnel from below and cannot be breached by a single block of erosion.
     */
    public static final int ROCKS_ABOVE_ROOF = 2;
    static final int SIDE_CLEARANCE = 1;
    static final int WALL_OFFSET = 2;
    /** 隧道灯按站序间隔设置；该间隔并非严格世界格距离。 */
    static final int LANTERN_INTERVAL = 10;
    /** 无采样或无地形时才用口部站数默认值；正常洞口长度按实际地形求解，避免短隧道全成露天沟。
 *
     * Fallback mouth length (stations) for the case where no terrain can be read.
     *
     * <p>0.9.064: this is <b>no longer the mouth length</b>. It used to be, and a fixed answer meant a
     * span of up to 27 stations was <em>entirely</em> mouth: no roof, no walls, no lanterns - a
     * five-wide, six-deep trench instead of a tunnel. The mouth is now derived from the grade an
     * approach road needs (see {@link #portalAdvance}); this number survives only as the answer for
     * {@code reason=no-sampler} / {@code reason=no-terrain}, i.e. when there is no terrain to measure.
     */
    static final int PORTAL_TRANSITION_SEGMENTS = 14;
    /**
     * Hard cap on one mouth section, in stations.
     *
     * <p><b>拍</b> (0.9.064): 32 stations x 8 blocks = 256 blocks. Past that the "mouth" is no longer a
     * mouth but the whole hillside, so the section is left to the cover test instead of being cut open
     * indefinitely. The cap also bounds {@link #portalAdvance} whatever the sampled depth says.
     */
    static final int PORTAL_MAX_SEGMENTS = 32;
    /** 保留最短喉口，避免隧道入口被直接封死。 */
    static final int PORTAL_MIN_SEGMENTS = 1;
    /** 观景隧道与水下跨越共用壳层尺寸，保持截面衔接。
 *
     * Viewing-dome dimensions for a glazed (scenic) tunnel. The width comes from the shared
     * shell so this vault and a submerged crossing's vault are the same shape.
     */
    static final int DOME_RADIUS = ScenicGlassShell.RADIUS;
    static final int DOME_SPRING = roofOffset() + 2 - DOME_RADIUS;
    /** 洞口只处理局部入口，不能以口部标记剥掉整座山。 */
    static final int MAX_PORTAL_CLEAR_HEIGHT = 16;
    static final AtomicLong PORTAL_SECTIONS = new AtomicLong();
    static final AtomicLong BLOCKED_SECTIONS = new AtomicLong();
    static final AtomicLong PROTECTED_GAP_SECTIONS = new AtomicLong();
    /** 站索引反查允许的坐标漂移（格）；站序列被重建过时会漂 1~2 格。 */
    static final int STATION_XZ_FALLBACK_TOLERANCE = 4;
    static final AtomicLong STATION_FALLBACK_HITS = new AtomicLong();
    static final AtomicLong STATION_FALLBACK_MISSES = new AtomicLong();
    static final Map<net.minecraft.server.level.ServerLevel, ConcurrentHashMap<SpanKey, SpanState>> SPAN_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 按世界与会话记跨度拒绝；硬保护独立成逐站缺口，其他拒绝按既定地表处理，不跨存档沿用。 */
    static final Map<net.minecraft.server.level.ServerLevel, Set<Long>> REJECTED_TUNNEL_SPANS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RoadTunnelBuilder() {
    }

    public static void processSegment(WorldGenLevel world, RoadData road, RoadSegmentPlacement segment,
            int segmentIndex, List<BlockPos> centers, int[] profile, Consumer<TunnelPlan> construction) {
        processSegment(world, road, segment, segmentIndex, centers, profile, false, construction);
    }

    public static void processSegment(WorldGenLevel world, RoadData road, RoadSegmentPlacement segment,
            int segmentIndex, List<BlockPos> centers, int[] profile, boolean externalJunction,
            Consumer<TunnelPlan> construction) {
        if (construction == null) return;
        Span span = findSpan(road == null ? null : road.spans(), centers, segmentIndex);
        boolean junction = externalJunction || isInSpan(road == null ? null : road.spans(), centers,
                segmentIndex, SpanType.TUNNEL_JUNCTION);
        if (span == null && junction) {
            span = new Span(Math.max(0, segmentIndex - 1), Math.min(centers.size() - 1, segmentIndex + 1));
        }
        if (span == null || world == null) {
            construction.accept(TunnelPlan.none());
            return;
        }
        LOGGER.info("【当前隧道跨度和路口位置】 " + "RoadWeaver tunnel span segment={} span={}..{} len={} center={},{} junction={}",
                segmentIndex, span.start(), span.end(), span.length(centers),
                segment == null || segment.middlePos() == null ? -1 : segment.middlePos().getX(),
                segment == null || segment.middlePos() == null ? -1 : segment.middlePos().getZ(),
                junction);
        SpanState state = spanState(world.getLevel(), road, span, centers);
        TunnelPlan plan;
        // 消费者可能立即铺路；先结束只读阶段的计时，避免把落块计进探测。
        try (var timing = TunnelReadTiming.begin("probe", segment == null ? null : segment.middlePos())) {
            prepareSpanState(world, state, road, centers, profile, span);
            plan = preflight(world, road, segment, segmentIndex, centers, profile,
                    span, state, junction, externalJunction);
            if (timing != null) timing.completed = true;
        }
        construction.accept(plan);
    }

    static void prepareSpanState(WorldGenLevel world, SpanState state, RoadData road,
            List<BlockPos> centers, int[] profile, Span span) {
        synchronized (state.lock) {
            if (state.decision == Decision.UNKNOWN) {
                // Tunnel spans are allowed to be arbitrarily long. Chunk
                // ownership already divides the excavation into safe sections.
                state.decision = span == null ? Decision.REJECTED : Decision.APPROVED;
            }
            state.prepareLanterns(road, centers, profile, span);
            // 0.9.064: 洞口段长按坡度推 —— 每个 span 只算一次、只打一行（见 announcePortalSplit）。
            state.preparePortal(world, road, centers, profile, span);
        }
    }

    static TunnelPlan preflight(WorldGenLevel world, RoadData road, RoadSegmentPlacement segment,
            int segmentIndex, List<BlockPos> centers, int[] profile, Span span, SpanState state,
            boolean junction, boolean externalJunction) {
        if (world == null || road == null || segment == null || centers == null || profile == null) {
            return TunnelPlan.blockedPlan();
        }
        if (state.decision != Decision.APPROVED) {
            return reject(world, road, centers, span, segmentIndex, "invalid-span", null);
        }
        // A span in which any station was already rejected is degraded as a
        // whole: every remaining station takes the surface-cut fallback so the
        // corridor never ends up as tunnel cavities separated by an unexcavated
        // one-station wall.
        if (span != null && isSpanRejected(world.getLevel(), road, span)) {
            return reject(world, road, centers, span, segmentIndex, "span_previously_rejected", null);
        }
        PlannedSection section = plannedSection(segment, segmentIndex, centers, profile, span.start());
        if (section == null) {
            return reject(world, road, centers, span, segmentIndex, "invalid-section", null);
        }
        boolean scenicWater = containsWater(world, section.interior())
                || containsWater(world, section.walls());
        // A tunnel span can legitimately touch a route endpoint when the
        // connection starts or ends inside a mountain. In that case there is
        // no sampled "stable surface mouth" inside the route, so the normal
        // overburden test would classify the endpoint as a sealed tunnel and
        // the first/last section could be skipped. Treat the boundary section
        // as the open mouth; the rest of the span remains a sealed tunnel.
        boolean endpointPortal = !junction
                && isTunnelEndpointPortal(centers, span, segmentIndex)
                && requiresOpenPortal(world, section);
        // A persisted centre point already carries the planned target Y, so
        // comparing centers[index].y with profile[index] cannot identify a
        // portal (it is normally zero).  Use the real terrain/shell test and
        // explicit span endpoints instead; deep tunnel sections must remain
        // sealed tunnel sections rather than being treated as giant ramps.
        // A planned tunnel must always connect to the approach road. The
        // first/last transition sections of every span are the mouths: even
        // when sampling cannot find a "stable surface mouth" (retained
        // deep-cut), those ends must open so the roadway can enter/exit the
        // mountain. Otherwise the road stops at the rock face and leaves the
        // gap the player sees.
        boolean portal = !junction && (endpointPortal
                || isSpanMouthSegment(state.portalSplit, span, segmentIndex)
                || requiresOpenPortal(world, section));
        SafetyCheck check;
        if (junction) {
            check = junctionSafe(world, road, section, state, externalJunction);
        } else if (portal) {
            check = portalSafe(world, road, section, state);
        } else {
            check = sectionSafe(world, road, section, state);
            if (!check.safe() && shouldOpenPortalTransition(
                    check.reason(), centers, profile, span, segmentIndex)) {
                SafetyCheck portalCheck = portalSafe(world, road, section, state);
                if (portalCheck.safe()) {
                    portal = true;
                    check = portalCheck;
                } else {
                    check = portalCheck;
                }
            }
        }
        if (!check.safe()) {
            if (isContinuityOnlyFailure(check.reason())) {
                LOGGER.info("【按连续洞身规则施工当前隧道站点】 " + "RoadWeaver tunnel forced continuous bore segment={} role={} reason={} center={},{}",
                        segmentIndex,
                        junction ? "JUNCTION" : (portal ? "PORTAL" : "SEALED"),
                        check.reason(),
                        segment == null || segment.middlePos() == null ? -1 : segment.middlePos().getX(),
                        segment == null || segment.middlePos() == null ? -1 : segment.middlePos().getZ());
                return new TunnelPlan(section.interior(), section.walls(), section.lantern(),
                        segmentIndex % LANTERN_INTERVAL == 0, road,
                        portal, junction, scenicWater, false, false);
            }
            if (isHardProtectionFailure(check.reason())) {
                if (!isHeadroomOnly(check.reason())) {
                    return protectedGapPlan(road, centers, span, segmentIndex,
                            check.reason(), check.position());
                }
                // N-10 乙（0.9.027，用户 2026-11-08 选定）：`protected-headroom` 只是
                // **头顶净空被一个方块挡住**，它没有理由让整站消失 —— 整站跳过等于在隧道墙上
                // 破一个 2 格宽的洞（真机 N-10：一个方块 (-143,97,208) 卡死连续两站 1151/1152）。
                // 改为照常建洞身：apply() 的逐列清障本来就会在这一列停在保护方块之前，
                // 那一列的顶板也随之被跳过 —— 结果从「墙上一个洞」变成「头顶一块石头没清」。
                // 注意：不要把它改回 protectedGapPlan，也不要退回 T-22 之前的整段降级
                // （那会产生「洞→未挖墙→洞」的残墙，是这条线最差的一档）。
                LOGGER.info("【洞顶净空否决已保留为洞身，跳过屋顶】 " + "RoadWeaver tunnel headroom veto kept as bore segment={} reason={} pos={} "
                                + "action=keep_bore_skip_roof",
                        segmentIndex, check.reason(), check.position());
            } else {
                return reject(world, road, centers, span, segmentIndex, check.reason(), check.position());
            }
        }
        if (LOGGER.isInfoEnabled()) {
            BlockPos center = segment == null ? null : segment.middlePos();
            int roadY = profile == null || segmentIndex < 0 || segmentIndex >= profile.length
                    ? -1 : profile[segmentIndex];
            int terrainY = center == null ? -1 :
                    TunnelReadTiming.terrain(world, center.getX(), center.getZ());
            LOGGER.info("【隧道断面角色和覆土高度】 " + "RoadWeaver tunnel section segment={} role={} roadY={} terrainY={} "
                            + "overburden={} center={},{}",
                    segmentIndex,
                    junction ? "JUNCTION" : (portal ? "PORTAL" : "SEALED"),
                    roadY, terrainY, terrainY - roadY,
                    center == null ? -1 : center.getX(),
                    center == null ? -1 : center.getZ());
        }
        return new TunnelPlan(section.interior(), section.walls(), section.lantern(),
                segmentIndex % LANTERN_INTERVAL == 0, road, portal, junction,
                scenicWater, false, false);
    }

    static SafetyCheck sectionSafe(WorldGenLevel world, RoadData road, PlannedSection section, SpanState state) {
        try {
            SafetyCheck roadbed = roadbedSafe(world, road, section);
            if (!roadbed.safe()) return roadbed;
            for (BlockPos column : section.interior()) {
                if (!columnAvailableWithoutLoading(world, column)) {
                    return SafetyCheck.failure("unavailable-loaded-column", column);
                }
                int terrain = TunnelReadTiming.terrain(world, column.getX(), column.getZ());
                // Overburden is rock, never water. The walkable surface answers "sea level + 1" for a water
                // column, so an undersea tunnel used to pass this gate with zero real cover.
                int rock = TunnelReadTiming.rock(world, column.getX(), column.getZ());
                if (column.getY() < world.getMinBuildHeight()
                        || column.getY() + roofOffset() >= world.getMaxBuildHeight()) {
                    return SafetyCheck.failure("invalid-section-height", column);
                }
                if (rock < column.getY() + minOverburden()) {
                    return SafetyCheck.failure("insufficient-overburden", column);
                }
                int fromDy = section.isRoadColumn(column) ? 0 : 1;
                for (int dy = fromDy; dy <= roofOffset(); dy++) {
                    BlockPos volume = column.above(dy);
                    BlockState block = TunnelReadTiming.block(world, volume);
                    if (!block.getFluidState().isEmpty()
                            && !block.getFluidState().is(FluidTags.WATER)) {
                        return SafetyCheck.failure("fluid-in-volume", volume);
                    }
                    if (isProtectedTunnelBlock(world, road, volume, block, state, terrain)) {
                        // T-22 分级净空:洞顶层(dy>=3)的保护方块不阻断贯通 ——
                        // apply() 只清空到保护块下方,dy=1..2 的空气层保留
                        // ≥2 格通行净空,该列放弃人工顶板。dy<=2(通行空间内)
                        // 的保护仍然拒绝,由逐站 PROTECTED_GAP 兜底。
                        if (dy >= 3) continue;
                        return SafetyCheck.failure("protected-block", volume);
                    }
                }
            }
            for (BlockPos wall : section.walls()) {
                if (!columnAvailableWithoutLoading(world, wall)) {
                    return SafetyCheck.failure("unavailable-loaded-wall-column", wall);
                }
                int terrain = TunnelReadTiming.terrain(world, wall.getX(), wall.getZ());
                int rock = TunnelReadTiming.rock(world, wall.getX(), wall.getZ());
                if (wall.getY() < world.getMinBuildHeight()
                        || wall.getY() + roofOffset() >= world.getMaxBuildHeight()) {
                    return SafetyCheck.failure("invalid-wall-height", wall);
                }
                if (rock < wall.getY() + minOverburden()) {
                    return SafetyCheck.failure("insufficient-wall-overburden", wall);
                }
                for (int dy = 0; dy <= roofOffset(); dy++) {
                    BlockPos volume = wall.above(dy);
                    BlockState block = TunnelReadTiming.block(world, volume);
                    if (!block.getFluidState().isEmpty()
                            && !block.getFluidState().is(FluidTags.WATER)) {
                        return SafetyCheck.failure("fluid-in-wall-volume", volume);
                    }
                    // Walls sit outside the bore; a genuinely hard-protected
                    // block in the shell only means apply() cannot place the
                    // stone cover on that cell. It must not gap the station —
                    // apply() skips protected wall volumes per block.
                }
            }
            return SafetyCheck.success();
        } catch (RuntimeException unavailableWorldgenColumn) {
            return SafetyCheck.failure("unavailable-worldgen-column", section.lantern());
        }
    }

    /** 在铺路器写入选定甲板后调用，避免壳层覆盖或悬离实际路面。 */
    public static void apply(WorldGenLevel world, TunnelPlan plan) {
        if (world == null || plan == null || !plan.active()) return;
        try (var timing = TunnelReadTiming.begin("apply", plan.interior().isEmpty() ? null : plan.interior().get(0))) {
            applyActive(world, plan);
            if (timing != null) timing.completed = true;
        }
    }

    private static void applyActive(WorldGenLevel world, TunnelPlan plan) {
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        BlockPos spanStart = plan.interior().isEmpty() ? null : plan.interior().get(0);
        double spanLength = scenicSpanLength(plan);
        int builtColumns = 0;
        int roofedColumns = 0;
        // 0.9.027（N-11 取证）：洞身净空到底清了多少格、有多少列没清到底、留了多少盏灯。
        // 用户方案要求「从地板往上清 tunnelClearHeight 格 × 断面宽，跳过灯笼」，
        // 这三个数就是这条要求唯一的可核对证据。
        int clearedCells = 0;
        int missedColumns = 0;
        int lanternsKept = 0;
        int minOverburden = Integer.MAX_VALUE;
        int maxOverburden = Integer.MIN_VALUE;
        for (BlockPos interior : plan.interior()) {
            // Per column, so a long tunnel fades instead of changing tint per section.
            BlockState glass = scenicGlassState(scenicProgress(interior, spanStart, spanLength));
            int terrain = TunnelReadTiming.terrain(world, interior.getX(), interior.getZ());
            // Rock, not the walkable surface: for a seabed column the latter is sea level + 1, which made
            // every undersea section read as "14..20 blocks of cover" while it had none.
            int rock = TunnelReadTiming.rock(world, interior.getX(), interior.getZ());
            int overburden = rock - interior.getY();
            minOverburden = Math.min(minOverburden, overburden);
            maxOverburden = Math.max(maxOverburden, overburden);
            // The bore must clear every station up to the roof line. A roof
            // block is placed exactly at roofOffset(), so leaving one original
            // terrain block just below it produced an internal wall inside the
            // tunnel and a dirty mouth at the endpoint. Portal and sealed
            // sections now share this continuous headroom.
            int clearTo = roofOffset();
            if (plan.portal() || plan.junction()) {
                AboveColumnClearer.stabilizeFallingCap(world, interior, plan.road(), clearTo + 1);
            }
            // T-22: 逐块保护防御。sectionSafe 已容忍洞顶层(dy>=3)的保护方块;
            // 清空到第一块真保护为止 —— 保留其下 dy>=1 的全部空气(≥2 格通行
            // 净空),其上保持原山体,该列不再放置人工顶板。
            int clearTop = 0;
            for (int dy = 1; dy <= clearTo; dy++) {
                BlockPos volume = interior.above(dy);
                // 0.9.027（N-11）：净空里已经挂着的灯笼一律保留（用户明确要求「不要清除灯笼」）。
                // 以前这里无条件 setBlock(AIR)，相邻 section 交错到同一列时会把灯一起削掉。
                if (TunnelReadTiming.block(world, volume).is(Blocks.LANTERN)) {
                    lanternsKept++;
                    clearTop = dy;
                    continue;
                }
                if (RoadConstructionSafety.isProtectedTunnelVolumeWithoutStructures(world, volume, terrain)) {
                    break;
                }
                // 长城列不参与隧道清障：隧道贴墙走时不能把墙内体素清成空气。
                // 只读否决 —— 隧道走向与规划完全不受影响，只是这一列不再往上清。
                if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                        net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CUT,
                        volume.getX(), volume.getY(), volume.getZ())) {
                    break;
                }
                world.setBlock(volume, Blocks.AIR.defaultBlockState(), 3);
                clearedCells++;
                clearTop = dy;
            }
            if (clearTop < clearTo) missedColumns++;
            // Place the roof when the mountain is tall enough to cover it, for
            // sealed and portal sections alike. A portal with no cover stays
            // open as the natural mouth, so the tunnel connects without a
            // canyon and without a floating roof.
            if (!plan.junction() && !plan.portal() && clearTop == roofOffset()) {
                BlockPos roof = interior.above(roofOffset());
                if (!RoadConstructionSafety.isProtectedTunnelVolumeWithoutStructures(world, roof, terrain) && !isWallOwned(roof)) {
                    world.setBlock(roof, plan.scenic() ? glass : stone, 3);
                    roofedColumns++;
                }
            } else if (!plan.junction() && !plan.portal() && overburden >= 1 && clearTop >= roofOffset() - 1) {
                // Task 13: shallow-cover tolerance. A column with 1..N
                // overburden below the roof line used to be left without
                // any cover (roofed=0/N), which let loose surface blocks
                // hang over the bore. Seat the roof into the surface block
                // instead: level with the terrain, never floating.
                // The surface block is the topmost SOLID one - using the walkable height here put the roof
                // on the water surface for a seabed column.
                //
                // ★ 0.9.027（N-11 真凶）：这里的封顶位置原来是裸的 `rock - 1`。
                //   当覆土比洞身净空还浅（1 <= overburden < roofOffset()）时，
                //   `rock - 1` 落在**洞身净空之内** —— 覆土 2 就是贴着地板放一块石砖
                //   （地面上直接长出一根 1 格砖柱），覆土 3 就是 2 格高处浮一块。
                //   这就是用户截图里「隧道地板上好多未清理的竖直砖柱」。
                //   规范：封顶永远不得低于洞顶线，只往上让不让往下塞。
                int roofLineY = interior.getY() + roofOffset();
                BlockPos shallowRoof = new BlockPos(interior.getX(), Math.max(rock - 1, roofLineY), interior.getZ());
                if (!isWallOwned(shallowRoof)
                        && !RoadConstructionSafety.isProtectedTunnelVolume(world, shallowRoof, plan.road(),
                        TunnelReadTiming.block(world, shallowRoof), terrain)) {
                    world.setBlock(shallowRoof, plan.scenic() ? glass : stone, 3);
                    roofedColumns++;
                }
            }
            builtColumns++;
        }
        if (plan.scenic()) {
            // Keep the pavement as the floor, but seal the sides and roof with
            // glass so an underwater tunnel becomes a transparent viewing tube.
            placeScenicDome(world, plan, spanStart, spanLength);
        } else if (plan.portal()) {
            clearLegacyPortalWalls(world, plan);
        } else if (!plan.junction()) {
            for (BlockPos wall : plan.walls()) {
                int terrain = TunnelReadTiming.terrain(world, wall.getX(), wall.getZ());
                // Skip a wall column only when the rock itself runs out *and* no open water sits above it.
                // The old test used the walkable surface, which for a seabed column is always far above the
                // roof, so the skip never fired there; and on a hillside it fired on the whole column,
                // leaving a one-column gap in the lining for the sea to pour through.
                if (TunnelReadTiming.rock(world, wall.getX(), wall.getZ())
                        < wall.getY() + roofOffset() && !isWaterAboveRoof(world, wall)) continue;
                for (int dy = 0; dy <= roofOffset(); dy++) {
                    BlockPos volume = wall.above(dy);
                    // A wall sits outside the bore. When one shell cell hits a
                    // genuinely hard-protected block, leave that cell natural
                    // instead of overwriting it or rejecting the whole section.
                    if (RoadConstructionSafety.isProtectedTunnelVolume(world, volume, plan.road(),
                            TunnelReadTiming.block(world, volume), terrain) || isWallOwned(volume)) {
                        continue;
                    }
                    world.setBlock(volume, stone, 3);
                }
            }
            // A sealed bore has to stay dry, not merely be carved out of the rock: see sealBore.
            if (ConfigService.get().tunnelDrySeal()) sealBore(world, plan);
        }
        // A glazed tunnel keeps its lanterns even when the section is a portal.
        //
        // `!plan.portal()` here suppressed the lights for exactly the sections a scenic
        // tunnel is made of: an underwater run is a near-surface portal by the seabed test,
        // so the viewing tube would have had no lamps at all. Scenic sections are enclosed by
        // the glass dome either way, so they are lit like any sealed section.
        boolean litSection = plan.scenic() || (!plan.portal() && !plan.junction());
        if (litSection && plan.lantern() != null && plan.placeLantern()) {
            BlockPos lantern = plan.lantern().above(clearHeight());
            if (!RoadConstructionSafety.isProtectedTunnelVolumeWithoutStructures(world, lantern, terrainHeightAt(world, plan.lantern())) && !isWallOwned(lantern)) {
                world.setBlock(lantern,
                        Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 3);
            }
        }
        BlockPos sample = plan.interior().get(0);
        LOGGER.info("【隧道断面落块、净空和保留灯笼统计】 " + "RoadWeaver tunnel built role={} columns={} roofed={}/{} overburden={}..{} "
                        + "cleared={} missed={} lanternsKept={} at {},{} y={}",
                plan.portal() ? "PORTAL" : (plan.junction() ? "JUNCTION" : "SEALED"),
                builtColumns, roofedColumns, plan.interior().size(),
                minOverburden, maxOverburden,
                clearedCells, missedColumns, lanternsKept,
                sample.getX(), sample.getZ(), sample.getY());
        if (plan.portal()) {
            long count = PORTAL_SECTIONS.incrementAndGet();
            if (count <= 8 || count % 128 == 0) {
                BlockPos center = plan.interior().get(plan.interior().size() / 2);
                LOGGER.info("【隧道洞口断面已敞开】 " + "RoadWeaver opened tunnel portal section at {},{} y={} count={}",
                        center.getX(), center.getZ(), center.getY(), count);
            }
        }
    }

    /** 兼容入口保留签名；洞口分类仍依赖真实地形或显式端点，不用已归一的中心Y自证。
 *
     * Kept for binary/source compatibility with the nearby transition code.
     * Portal classification must use actual terrain (see requiresOpenPortal)
     * or an explicit span endpoint, never the already-normalized centre Y.
     */
    static boolean isPortalSection(List<BlockPos> centers, int[] profile, int segmentIndex) {
        return false;
    }

    static boolean isTunnelEndpointPortal(List<BlockPos> centers,
                                                  Span span,
                                                  int segmentIndex) {
        if (centers == null || centers.isEmpty() || span == null) return false;
        int first = Math.min(span.start(), span.end());
        int last = Math.max(span.start(), span.end());
        if (segmentIndex != first && segmentIndex != last) return false;
        return (first <= 0 && segmentIndex == first)
                || (last >= centers.size() - 1 && segmentIndex == last);
    }

    /** 隧道跨度喉口保持开放，即使其位于山内也保留可进入口。 */
    static boolean isSpanMouthSegment(PortalSplit split, Span span, int segmentIndex) {
        if (split != null) {
            return Math.abs(segmentIndex - split.spanFirst()) < split.portalStart()
                    || Math.abs(segmentIndex - split.spanLast()) < split.portalEnd();
        }
        // 判定结果缺失（世界/span 异常）时退回历史口径：绝不因为缺地形把整段隧道改成洞身。
        if (span == null) return false;
        int first = Math.min(span.start(), span.end());
        int last = Math.max(span.start(), span.end());
        return Math.abs(segmentIndex - first) < PORTAL_TRANSITION_SEGMENTS
                || Math.abs(segmentIndex - last) < PORTAL_TRANSITION_SEGMENTS;
    }

    // ── 0.9.064：洞口段长改按坡度推（本版唯一一处行为改动）────────────────────

    /**
     * 洞口段长唯一需要的地形输入：某一世界列的地表高度；拿不到（世界缺失 / 区块不可采样）就返回
     * {@code null}。
     *
     * <p>生产实现直接用本类**已有的入参** {@code WorldGenLevel}，经
     * {@link RoadConstructionSafety#terrainHeight} 读地形 —— 与 {@code sectionSafe} /
     * {@code requiresOpenPortal} 判覆土用的是<b>同一个读法</b>。洞口段长不能和「头顶有没有岩石」
     * 用两套地形：两处口径一旦不同，同一站就会自相矛盾（洞口段说该明挖、覆土说头顶有 20 格岩石）。
     */
    @FunctionalInterface
    interface PortalTerrain {
        Integer surfaceAt(int x, int z);
    }

    /**
     * 一次洞口段长判定的结果。
     *
     * @param segments 洞口段长（站）
     * @param reason   {@code null} = 按坡度推出来的；{@code no-sampler} / {@code no-terrain} = 退回兜底 14 站
     */
    record PortalAdvance(int segments, String reason) {
        boolean fallback() { return reason != null; }

        String logValue() { return reason == null ? "slope" : reason; }
    }

    /**
     * 一个 span 的洞口判定结果（洞身两端对称处理）。
     *
     * <p>{@code openCutOnly = true} 表示洞身长度 ≤ 0 —— 判据判出了隧道，施工端却整段都是明挖。
     * 这一档必须看得见：{@link #announcePortalSplit} 用 {@code WARN} 打 {@code span=} 与 {@code len=}。
     */
    record PortalSplit(int spanFirst,
                       int spanLast,
                       int stations,
                       int portalStart,
                       int portalEnd,
                       int bodyStart,
                       int bodyEnd,
                       boolean openCutOnly,
                       String reason) {

        int bodyLength() { return bodyEnd - bodyStart + 1; }

        /** {@code [TUNNEL-PORTAL]} 的完整一行（不含日志级别/时间前缀）。 */
        String logLine() {
            return "[TUNNEL-PORTAL] span=" + spanFirst + ".." + spanLast
                    + " len=" + stations
                    + " portalStart=" + portalStart
                    + " portalEnd=" + portalEnd
                    + " bodyStart=" + bodyStart
                    + " bodyEnd=" + bodyEnd
                    + " openCutOnly=" + openCutOnly
                    + " reason=" + logValue();
        }

        /** 判出隧道但洞身为空时的那一行（必须 WARN）。 */
        String openCutOnlyLine() {
            return "[TUNNEL-PORTAL] openCutOnly=true span=" + spanFirst + ".." + spanLast
                    + " len=" + stations
                    + " portalStart=" + portalStart
                    + " portalEnd=" + portalEnd
                    + " bodyLen=" + bodyLength()
                    + " reason=" + logValue();
        }

        private String logValue() { return reason == null ? "slope" : reason; }
    }

    /**
     * 洞口段长（站）= 从洞身端点往外，地形还能以允许坡度开出引道的站数。
     *
     * <p>公式（本版唯一一处「按坡度推」的实现）：
     * <pre>
     *   depth    = max( 洞口站各路面列的地形高度 − 该列路面高度 )
     *   rise/站  = RoadConstants.DEFAULT_SOFT_GRADE_LIMIT(0.08) × 站距(格)
     *   segments = clamp( ceil(depth / rise/站), 1, 32 )
     * </pre>
     * 洞口埋得越深（山体在洞口抬得越陡）引道越长；平坦地形 {@code depth <= 0} ⇒ 1 站。
     *
     * <p><b>推</b>的：坡度取既有 {@code DEFAULT_SOFT_GRADE_LIMIT}（A* 开始付坡罚的坡度，引道再陡
     * 就不会被选出来）；量纲与规划侧 {@code TunnelEnvelopeCriterion#evaluate} 的
     * {@code requiredRampStations = ceil(promMax / (portalMaxGrade × stationSpacing))} 完全同口径。
     * <b>拍</b>的：{@link #PORTAL_MAX_SEGMENTS} = 32（上限，防在山脚无限延伸）与
     * {@link #PORTAL_MIN_SEGMENTS} = 1（洞口至少一站）。站距用<b>实测</b>相邻站距而不是名义 8 格 ——
     * 坡度是「升 / 水平」，分母必须是真的水平距离。
     *
     * @param terrain           地形读取；{@code null} ⇒ 退回 14 站并记 {@code no-sampler}
     * @param mouthRoad         洞口站的路面列（含路面高度）；空 ⇒ 退回 14 站并记 {@code no-terrain}
     * @param stationStepBlocks 实测站距（格），非正数时用 {@link RoadConstants#DEFAULT_PATH_GRID_STEP}
     */
    static PortalAdvance portalAdvance(PortalTerrain terrain, List<BlockPos> mouthRoad,
                                       double stationStepBlocks) {
        if (terrain == null) {
            return new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-sampler");
        }
        if (mouthRoad == null || mouthRoad.isEmpty()) {
            return new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-terrain");
        }
        double step = stationStepBlocks > 1.0E-6D
                ? stationStepBlocks
                : Math.max(1, RoadConstants.DEFAULT_PATH_GRID_STEP);
        int depth;
        try {
            depth = 0;
            for (BlockPos column : mouthRoad) {
                if (column == null) {
                    return new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-terrain");
                }
                Integer surface = terrain.surfaceAt(column.getX(), column.getZ());
                if (surface == null) {
                    return new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-terrain");
                }
                depth = Math.max(depth, surface - column.getY());
            }
        } catch (RuntimeException unavailableTerrain) {
            // 未加载列 / 世界生成边界上采样会抛：洞口段长退回历史值，绝不因此否掉整段隧道。
            return new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-terrain");
        }
        if (depth <= 0) {
            // 洞口站的路面已经在地表或地表之上：没有引道要挖，只要一站让路走进洞身。
            return new PortalAdvance(PORTAL_MIN_SEGMENTS, null);
        }
        double risePerStation = RoadConstants.DEFAULT_SOFT_GRADE_LIMIT * step;
        if (!(risePerStation > 0.0D)) {
            return new PortalAdvance(PORTAL_MAX_SEGMENTS, null);
        }
        int needed = (int) Math.ceil(depth / risePerStation);
        int segments = Math.max(PORTAL_MIN_SEGMENTS, Math.min(PORTAL_MAX_SEGMENTS, needed));
        return new PortalAdvance(segments, null);
    }

    /** 把一个 span 两端的洞口站长折成洞身区间（两端对称处理；洞身为空即 openCutOnly）。 */
    static PortalSplit portalSplit(int spanFirst, int spanLast, PortalAdvance start, PortalAdvance end) {
        int first = Math.min(spanFirst, spanLast);
        int last = Math.max(spanFirst, spanLast);
        int stations = Math.max(1, last - first + 1);
        PortalAdvance head = start == null ? new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-sampler") : start;
        PortalAdvance tail = end == null ? new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-sampler") : end;
        // 单端洞口段长不得超过整段站数（否则日志里的 portalStart 会比 span 本身还长）。
        int portalStart = Math.max(PORTAL_MIN_SEGMENTS, Math.min(stations, head.segments()));
        int portalEnd = Math.max(PORTAL_MIN_SEGMENTS, Math.min(stations, tail.segments()));
        int bodyStart = first + portalStart;
        int bodyEnd = last - portalEnd;
        boolean openCutOnly = bodyEnd - bodyStart + 1 <= 0;
        String reason = head.reason() != null ? head.reason() : tail.reason();
        return new PortalSplit(first, last, stations, portalStart, portalEnd,
                bodyStart, bodyEnd, openCutOnly, reason);
    }

    /** 判定一个 span 的洞口段长；地形拿不到时退回历史 14 站并记 reason。 */
    static PortalSplit resolvePortalSplit(WorldGenLevel world, RoadData road, List<BlockPos> centers,
                                          int[] profile, Span span) {
        if (span == null) return null;
        int first = Math.min(span.start(), span.end());
        int last = Math.max(span.start(), span.end());
        PortalTerrain terrain = terrainOf(world);
        PortalAdvance start;
        PortalAdvance end;
        try {
            start = portalAdvance(terrain, mouthRoad(road, centers, profile, span, first),
                    stationStep(centers, first, 1));
            end = portalAdvance(terrain, mouthRoad(road, centers, profile, span, last),
                    stationStep(centers, last, -1));
        } catch (RuntimeException unavailableSpan) {
            start = new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-terrain");
            end = new PortalAdvance(PORTAL_TRANSITION_SEGMENTS, "no-terrain");
        }
        return portalSplit(first, last, start, end);
    }

    /** 生产地形读取：沿用本类已有的 {@code WorldGenLevel} 入参，读法与被覆土判定完全相同。 */
    static PortalTerrain terrainOf(WorldGenLevel world) {
        return world == null ? null : (x, z) -> TunnelReadTiming.terrain(world, x, z);
    }

    /** 洞口站的路面列（含路面高度）；取不到就返回 {@code null}（⇒ 退回 14 站）。 */
    static List<BlockPos> mouthRoad(RoadData road, List<BlockPos> centers, int[] profile,
                                            Span span, int station) {
        if (road == null || road.roadSegmentList() == null) return null;
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        if (station < 0 || station >= segments.size()) return null;
        PlannedSection mouth = plannedSection(segments.get(station), station, centers, profile, span.start());
        return mouth == null ? null : mouth.road();
    }

    /** 实测站距（格）：洞口站与相邻内侧站的水平距离；取不到就用名义步长。 */
    static double stationStep(List<BlockPos> centers, int station, int inward) {
        if (centers == null) return RoadConstants.DEFAULT_PATH_GRID_STEP;
        int other = station + inward;
        if (station < 0 || station >= centers.size() || other < 0 || other >= centers.size()) {
            return RoadConstants.DEFAULT_PATH_GRID_STEP;
        }
        BlockPos here = centers.get(station);
        BlockPos next = centers.get(other);
        if (here == null || next == null) return RoadConstants.DEFAULT_PATH_GRID_STEP;
        double step = Math.hypot(here.getX() - next.getX(), here.getZ() - next.getZ());
        return step > 1.0E-6D ? step : RoadConstants.DEFAULT_PATH_GRID_STEP;
    }

    /**
     * 打日志并返回判定结果（每个 span 只调一次）。
     *
     * <p>{@code openCutOnly=true} 用 {@code WARN}：判据判出了隧道、施工端却整段明挖 —— 这是最需要
     * 在日志里被看见的一档，{@code span=} 与 {@code len=} 必须同时出现，否则无法定位是哪条路。
     */
    static PortalSplit announcePortalSplit(PortalSplit split) {
        if (split == null) return null;
        LOGGER.info("【隧道洞口与洞身分段结果】 {}", split.logLine());
        if (split.openCutOnly()) {
            LOGGER.warn("【整段隧道仅作明挖，没有封闭洞身】 {}", split.openCutOnlyLine());
        }
        return split;
    }


    /** 洞内排水统计转交专用安全规则，避免门面重复维护计数。 */
    static final AtomicLong DRIED_BORE_CELLS = new AtomicLong();
    /** 壳层封水统计转交专用安全规则，避免两个实现口径分离。 */
    static final AtomicLong SEALED_SHELL_CELLS = new AtomicLong();
    static final int[][] BORE_SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};




    /** 只有硬保护可停止洞体；其他失败不能作为已确认建筑缺口。
 * Geometry/load failures must not split a mountain tunnel; only hard
     *  structural protection (protected-*) is allowed to stop the bore. */
    /** 仅读取当前区域地形；无世界或位置返回未知哨兵，不触发加载。 */
    static int terrainHeightAt(WorldGenLevel world, BlockPos pos) {
        return pos == null || world == null
                ? Integer.MIN_VALUE
                : TunnelReadTiming.terrain(world, pos.getX(), pos.getZ());
    }

    /**
     * Genuine hard protection confirmed by {@code sectionSafe}/{@code portalSafe}:
     * a real bedrock, block-entity or FEATURES_CANNOT_REPLACE block, a confirmed
     * structure template block, a reserved trunk at surface level, or a
     * sanctuary. It stops the bore at exactly this station; the span keeps
     * building as a tunnel around it and the station is skipped as an explicit
     * protected gap instead of degrading the whole span to the surface-cut
     * fallback, which under a mountain is neither continuous nor repeatable.
     * T-22: {@code protected-headroom}(portal/junction 的 clearance 检查)同样
     * 属于真硬保护,改为逐站缺口 —— 旧行为把它计入整段降级,是穿山隧道
     * "洞→未挖墙→洞"残墙的第二来源。
     */
    static boolean isHardProtectionFailure(String reason) {
        return "protected-block".equals(reason)
                || "protected-roadbed".equals(reason)
                // 0.9.027：AboveColumnClearer 会把它写成 `protected-headroom:<blockId>`，
                // 把挡住净空的方块名带进日志（N-10 的取证需求），所以这里必须按前缀判。
                || (reason != null && reason.startsWith("protected-headroom"));
    }

    /** {@code protected-headroom[:blockId]} —— 只否掉头顶净空，不否掉整站（见 preflight 里的 N-10 乙）。 */
    static boolean isHeadroomOnly(String reason) {
        return reason != null && reason.startsWith("protected-headroom");
    }

    static TunnelPlan protectedGapPlan(RoadData road, List<BlockPos> centers, Span span,
            int segmentIndex, String reason, BlockPos position) {
        long count = PROTECTED_GAP_SECTIONS.incrementAndGet();
        if (count <= 32 || count % 128 == 0) {
            BlockPos center = centers != null && segmentIndex >= 0 && segmentIndex < centers.size()
                    ? centers.get(segmentIndex) : null;
            LOGGER.warn("【隧道站点受保护，跳过该站并保留隧道跨度类型】 " + "RoadWeaver tunnel PROTECTED_GAP road={}->{} segment={} span={}..{} reason={} center={} pos={} "
                            + "count={} action=skip_station span_stays_tunnel",
                    road == null ? RoadData.NO_OWNER_2D : road.ownerA2dKey(),
                    road == null ? RoadData.NO_OWNER_2D : road.ownerB2dKey(),
                    segmentIndex, span == null ? -1 : span.start(), span == null ? -1 : span.end(),
                    reason, center, position, count);
        }
        return TunnelPlan.protectedGapPlan();
    }

    static TunnelPlan reject(WorldGenLevel world, RoadData road, List<BlockPos> centers, Span span,
            int segmentIndex, String reason, BlockPos position) {
        long count = BLOCKED_SECTIONS.incrementAndGet();
        if (span != null && road != null && world != null) {
            // Task: whole-span degradation. One rejected station marks the
            // entire span for the surface-cut fallback so no unexcavated wall
            // is left between two excavated cavities.
            markSpanRejected(world.getLevel(), road, span);
        }
        if (count <= 32 || count % 128 == 0) {
            BlockPos center = centers != null && segmentIndex >= 0 && segmentIndex < centers.size()
                    ? centers.get(segmentIndex) : null;
            LOGGER.warn("【隧道断面施工被阻挡】 " + "RoadWeaver blocked tunnel section road={}->{} segment={} span={}..{} reason={} center={} pos={} count={}",
                    road == null ? RoadData.NO_OWNER_2D : road.ownerA2dKey(),
                    road == null ? RoadData.NO_OWNER_2D : road.ownerB2dKey(),
                    segmentIndex, span == null ? -1 : span.start(), span == null ? -1 : span.end(),
                    reason, center, position, count);
        }
        return TunnelPlan.blockedPlan();
    }

    /** 拒绝记录绑定道路的稳定隧道跨度身份，避免一条路影响另一条路。 */
    static long spanKey(RoadData road, Span span) {
        long fingerprint = road == null ? 0L : net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage.computeFingerprint(road);
        int start = span == null ? -1 : span.start();
        int end = span == null ? -1 : span.end();
        return fingerprint * 1_000_003L + start * 31L + end;
    }

    /** 只读取当前世界已拒绝跨度，不把全JVM历史当当前状态。 */
    static boolean isSpanRejected(net.minecraft.server.level.ServerLevel level, RoadData road, Span span) {
        if (level == null || road == null || span == null) return false;
        Set<Long> rejected;
        synchronized (REJECTED_TUNNEL_SPANS) { rejected = REJECTED_TUNNEL_SPANS.get(level); }
        return rejected != null && rejected.contains(spanKey(road, span));
    }

    /** 跨度拒绝只记录本世界会话，避免跨维度和重开污染。 */
    static void markSpanRejected(net.minecraft.server.level.ServerLevel level, RoadData road, Span span) {
        if (level == null || road == null || span == null) return;
        Set<Long> rejected;
        synchronized (REJECTED_TUNNEL_SPANS) {
            rejected = REJECTED_TUNNEL_SPANS.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet());
        }
        rejected.add(spanKey(road, span));
    }



    /** 玻璃颜色委托共用往返渐变，保持隧道与淹没桥面衔接。
 *
     * Glass colour for one point along a glazed tunnel, as a round-trip gradient.
     *
     * <p>Delegates to the shared shell so a submerged bridge deck and a bored tunnel use the
     * same palette and the same fade.</p>
     *
     * @param progress 0 at the span start, 1 at its end; clamped
     */
    /** 从已有洞内坐标选最近点，避免以玻璃渐变进度误当最近距离。 */
    static BlockPos nearestInterior(BlockPos target, List<BlockPos> interior) {
        if (target == null || interior == null || interior.isEmpty()) return null;
        BlockPos nearest = null;
        long distance = Long.MAX_VALUE;
        for (BlockPos candidate : interior) {
            if (candidate == null) continue;
            long dx = (long) target.getX() - candidate.getX();
            long dz = (long) target.getZ() - candidate.getZ();
            long current = dx * dx + dz * dz;
            if (current < distance) {
                distance = current;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** 运行期回填可检查已加载区块，禁止为预检申请加载新区块。 */
    public static void clearForLevel(net.minecraft.server.level.ServerLevel level) {
        if (level == null) return;
        synchronized (SPAN_STATES) { SPAN_STATES.remove(level); }
        synchronized (REJECTED_TUNNEL_SPANS) { REJECTED_TUNNEL_SPANS.remove(level); }
    }

    public static void clearAll() {
        synchronized (SPAN_STATES) { SPAN_STATES.clear(); }
        synchronized (REJECTED_TUNNEL_SPANS) { REJECTED_TUNNEL_SPANS.clear(); }
        TunnelClearLedger.reset();
    }

}