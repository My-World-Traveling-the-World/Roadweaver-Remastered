package net.shiroha233.roadweaver.pathlogic.tunnel;

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

/**
 * Conservative tunnel construction for persisted TUNNEL spans. Each section
 * is checked before excavation; an unsafe section is left untouched instead
 * of being paved as an ordinary road inside solid terrain.
 */
public final class RoadTunnelBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** The historical clear height, still the default in the config. */
    static final int DEFAULT_CLEAR_HEIGHT = 5;
    /**
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

    /** Roof line above the deck: one above the clear height, so the roof block closes the bore. */
    public static int roofOffset() { return clearHeight() + 1; }

    /** Overburden a section needs before it may be sealed rather than treated as a portal. */
    public static int minOverburden() { return roofOffset() + 1; }
    /**
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
    /** One lantern roughly every 10 tunnel stations (stations are ~1 block apart). */
    private static final int LANTERN_INTERVAL = 10;
    /**
     * Fallback mouth length (stations) for the case where no terrain can be read.
     *
     * <p>0.9.064: this is <b>no longer the mouth length</b>. It used to be, and a fixed answer meant a
     * span of up to 27 stations was <em>entirely</em> mouth: no roof, no walls, no lanterns - a
     * five-wide, six-deep trench instead of a tunnel. The mouth is now derived from the grade an
     * approach road needs (see {@link #portalAdvance}); this number survives only as the answer for
     * {@code reason=no-sampler} / {@code reason=no-terrain}, i.e. when there is no terrain to measure.
     */
    private static final int PORTAL_TRANSITION_SEGMENTS = 14;
    /**
     * Hard cap on one mouth section, in stations.
     *
     * <p><b>拍</b> (0.9.064): 32 stations x 8 blocks = 256 blocks. Past that the "mouth" is no longer a
     * mouth but the whole hillside, so the section is left to the cover test instead of being cut open
     * indefinitely. The cap also bounds {@link #portalAdvance} whatever the sampled depth says.
     */
    private static final int PORTAL_MAX_SEGMENTS = 32;
    /** Shortest mouth section, in stations: the throat has to exist for the road to enter the bore. */
    private static final int PORTAL_MIN_SEGMENTS = 1;
    /**
     * Viewing-dome dimensions for a glazed (scenic) tunnel. The width comes from the shared
     * shell so this vault and a submerged crossing's vault are the same shape.
     */
    private static final int DOME_RADIUS = ScenicGlassShell.RADIUS;
    private static final int DOME_SPRING = roofOffset() + 2 - DOME_RADIUS;
    /** A portal is a local mouth, never an instruction to strip an entire mountain. */
    private static final int MAX_PORTAL_CLEAR_HEIGHT = 16;
    private static final AtomicLong PORTAL_SECTIONS = new AtomicLong();
    private static final AtomicLong BLOCKED_SECTIONS = new AtomicLong();
    private static final AtomicLong PROTECTED_GAP_SECTIONS = new AtomicLong();
    /** 站索引反查允许的坐标漂移（格）；站序列被重建过时会漂 1~2 格。 */
    private static final int STATION_XZ_FALLBACK_TOLERANCE = 4;
    private static final AtomicLong STATION_FALLBACK_HITS = new AtomicLong();
    private static final AtomicLong STATION_FALLBACK_MISSES = new AtomicLong();
    private static final Map<net.minecraft.server.level.ServerLevel, ConcurrentHashMap<SpanKey, SpanState>> SPAN_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Spans in which at least one station was rejected for a non-protection
     * reason (fluids, invalid span/section geometry). Genuine hard protection
     * no longer lands here: it becomes a per-station PROTECTED_GAP so the span
     * keeps building as a tunnel. Every remaining station of a rejected span
     * still takes the surface-cut fallback so a corridor is never left as
     * tunnel cavities separated by an unexcavated wall.
     *
     * <p>0.8.365 (S1): keyed by level, like {@link #SPAN_STATES}. Previously a
     * single flat {@code ConcurrentHashMap<Long, Boolean>} that neither
     * {@code clearForLevel} nor {@code clearAll} ever touched, so a span
     * rejected once stayed rejected for the whole JVM session and leaked
     * across dimensions and worlds. The rejection is a per-session,
     * per-level runtime observation, not durable state.</p>
     */
    private static final Map<net.minecraft.server.level.ServerLevel, Set<Long>> REJECTED_TUNNEL_SPANS =
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
        LOGGER.info("RoadWeaver tunnel span segment={} span={}..{} len={} center={},{} junction={}",
                segmentIndex, span.start(), span.end(), span.length(centers),
                segment == null || segment.middlePos() == null ? -1 : segment.middlePos().getX(),
                segment == null || segment.middlePos() == null ? -1 : segment.middlePos().getZ(),
                junction);
        SpanState state = spanState(world.getLevel(), road, span, centers);
        prepareSpanState(world, state, road, centers, profile, span);
        construction.accept(preflight(world, road, segment, segmentIndex, centers, profile,
                span, state, junction, externalJunction));
    }

    private static void prepareSpanState(WorldGenLevel world, SpanState state, RoadData road,
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

    private static TunnelPlan preflight(WorldGenLevel world, RoadData road, RoadSegmentPlacement segment,
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
                LOGGER.info("RoadWeaver tunnel forced continuous bore segment={} role={} reason={} center={},{}",
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
                LOGGER.info("RoadWeaver tunnel headroom veto kept as bore segment={} reason={} pos={} "
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
                    RoadConstructionSafety.terrainHeight(world, center.getX(), center.getZ());
            LOGGER.info("RoadWeaver tunnel section segment={} role={} roadY={} terrainY={} "
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

    private static SafetyCheck sectionSafe(WorldGenLevel world, RoadData road, PlannedSection section, SpanState state) {
        try {
            SafetyCheck roadbed = roadbedSafe(world, road, section);
            if (!roadbed.safe()) return roadbed;
            for (BlockPos column : section.interior()) {
                if (!columnAvailableWithoutLoading(world, column)) {
                    return SafetyCheck.failure("unavailable-loaded-column", column);
                }
                int terrain = RoadConstructionSafety.terrainHeight(world, column.getX(), column.getZ());
                // Overburden is rock, never water. The walkable surface answers "sea level + 1" for a water
                // column, so an undersea tunnel used to pass this gate with zero real cover.
                int rock = RoadConstructionSafety.rockSurfaceHeight(world, column.getX(), column.getZ());
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
                    BlockState block = world.getBlockState(volume);
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
                int terrain = RoadConstructionSafety.terrainHeight(world, wall.getX(), wall.getZ());
                int rock = RoadConstructionSafety.rockSurfaceHeight(world, wall.getX(), wall.getZ());
                if (wall.getY() < world.getMinBuildHeight()
                        || wall.getY() + roofOffset() >= world.getMaxBuildHeight()) {
                    return SafetyCheck.failure("invalid-wall-height", wall);
                }
                if (rock < wall.getY() + minOverburden()) {
                    return SafetyCheck.failure("insufficient-wall-overburden", wall);
                }
                for (int dy = 0; dy <= roofOffset(); dy++) {
                    BlockPos volume = wall.above(dy);
                    BlockState block = world.getBlockState(volume);
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

    /** Call after SegmentPaver has put down the selected road deck. */
    public static void apply(WorldGenLevel world, TunnelPlan plan) {
        if (world == null || plan == null || !plan.active()) return;
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
            int terrain = RoadConstructionSafety.terrainHeight(world, interior.getX(), interior.getZ());
            // Rock, not the walkable surface: for a seabed column the latter is sea level + 1, which made
            // every undersea section read as "14..20 blocks of cover" while it had none.
            int rock = RoadConstructionSafety.rockSurfaceHeight(world, interior.getX(), interior.getZ());
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
                if (world.getBlockState(volume).is(Blocks.LANTERN)) {
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
                        world.getBlockState(shallowRoof), terrain)) {
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
                int terrain = RoadConstructionSafety.terrainHeight(world, wall.getX(), wall.getZ());
                // Skip a wall column only when the rock itself runs out *and* no open water sits above it.
                // The old test used the walkable surface, which for a seabed column is always far above the
                // roof, so the skip never fired there; and on a hillside it fired on the whole column,
                // leaving a one-column gap in the lining for the sea to pour through.
                if (RoadConstructionSafety.rockSurfaceHeight(world, wall.getX(), wall.getZ())
                        < wall.getY() + roofOffset() && !isWaterAboveRoof(world, wall)) continue;
                for (int dy = 0; dy <= roofOffset(); dy++) {
                    BlockPos volume = wall.above(dy);
                    // A wall sits outside the bore. When one shell cell hits a
                    // genuinely hard-protected block, leave that cell natural
                    // instead of overwriting it or rejecting the whole section.
                    if (RoadConstructionSafety.isProtectedTunnelVolume(world, volume, plan.road(),
                            world.getBlockState(volume), terrain) || isWallOwned(volume)) {
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
        LOGGER.info("RoadWeaver tunnel built role={} columns={} roofed={}/{} overburden={}..{} "
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
                LOGGER.info("RoadWeaver opened tunnel portal section at {},{} y={} count={}",
                        center.getX(), center.getZ(), center.getY(), count);
            }
        }
    }

    /**
     * Kept for binary/source compatibility with the nearby transition code.
     * Portal classification must use actual terrain (see requiresOpenPortal)
     * or an explicit span endpoint, never the already-normalized centre Y.
     */
    private static boolean isPortalSection(List<BlockPos> centers, int[] profile, int segmentIndex) {
        return false;
    }

    private static boolean isTunnelEndpointPortal(List<BlockPos> centers,
                                                  Span span,
                                                  int segmentIndex) {
        if (centers == null || centers.isEmpty() || span == null) return false;
        int first = Math.min(span.start(), span.end());
        int last = Math.max(span.start(), span.end());
        if (segmentIndex != first && segmentIndex != last) return false;
        return (first <= 0 && segmentIndex == first)
                || (last >= centers.size() - 1 && segmentIndex == last);
    }

    /** The throat of a tunnel is always an open mouth, even inside a mountain. */
    private static boolean isSpanMouthSegment(PortalSplit split, Span span, int segmentIndex) {
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
     * @param stationStepBlocks 实测站距（格），非正数时用 {@link RoadConstants#DEFAULT_ASTAR_STEP}
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
                : Math.max(1, RoadConstants.DEFAULT_ASTAR_STEP);
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
        return world == null ? null : (x, z) -> RoadConstructionSafety.terrainHeight(world, x, z);
    }

    /** 洞口站的路面列（含路面高度）；取不到就返回 {@code null}（⇒ 退回 14 站）。 */
    private static List<BlockPos> mouthRoad(RoadData road, List<BlockPos> centers, int[] profile,
                                            Span span, int station) {
        if (road == null || road.roadSegmentList() == null) return null;
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        if (station < 0 || station >= segments.size()) return null;
        PlannedSection mouth = plannedSection(segments.get(station), station, centers, profile, span.start());
        return mouth == null ? null : mouth.road();
    }

    /** 实测站距（格）：洞口站与相邻内侧站的水平距离；取不到就用名义步长。 */
    private static double stationStep(List<BlockPos> centers, int station, int inward) {
        if (centers == null) return RoadConstants.DEFAULT_ASTAR_STEP;
        int other = station + inward;
        if (station < 0 || station >= centers.size() || other < 0 || other >= centers.size()) {
            return RoadConstants.DEFAULT_ASTAR_STEP;
        }
        BlockPos here = centers.get(station);
        BlockPos next = centers.get(other);
        if (here == null || next == null) return RoadConstants.DEFAULT_ASTAR_STEP;
        double step = Math.hypot(here.getX() - next.getX(), here.getZ() - next.getZ());
        return step > 1.0E-6D ? step : RoadConstants.DEFAULT_ASTAR_STEP;
    }

    /**
     * 打日志并返回判定结果（每个 span 只调一次）。
     *
     * <p>{@code openCutOnly=true} 用 {@code WARN}：判据判出了隧道、施工端却整段明挖 —— 这是最需要
     * 在日志里被看见的一档，{@code span=} 与 {@code len=} 必须同时出现，否则无法定位是哪条路。
     */
    static PortalSplit announcePortalSplit(PortalSplit split) {
        if (split == null) return null;
        LOGGER.info(split.logLine());
        if (split.openCutOnly()) {
            LOGGER.warn(split.openCutOnlyLine());
        }
        return split;
    }

    /**
     * Whether the bore itself is open to the sky at this station.
     *
     * <p>Tested on the roadway's own columns rather than on the whole shell: a station whose roadside wall
     * column happens to graze the surface is still a covered tunnel over the road, and letting one such
     * column veto the station is what turned whole stretches into a five-wide trench - no roof, no walls, no
     * lanterns, just an open cut with a tunnel's profile.
     *
     * <p>Open water above the bore counts as cover. A seabed station always reads "almost no rock above",
     * and that reading must never mean "cut it open": it is exactly the case where the roof is the only
     * thing keeping the ocean out.
     */
    private static boolean requiresOpenPortal(WorldGenLevel world, PlannedSection section) {
        if (world == null || section == null) return false;
        try {
            for (BlockPos column : section.road()) {
                int rock = RoadConstructionSafety.rockSurfaceHeight(world, column.getX(), column.getZ());
                if (rock > column.getY() + roofOffset()) continue;
                if (isWaterAboveRoof(world, column)) continue;
                return true;
            }
        } catch (RuntimeException unavailableWorldgenColumn) {
            return false;
        }
        return false;
    }

    /** Water cells removed from inside a sealed bore. */
    private static final AtomicLong DRIED_BORE_CELLS = new AtomicLong();
    /** Water cells in the shell that the lining replaced. */
    private static final AtomicLong SEALED_SHELL_CELLS = new AtomicLong();
    private static final int[][] BORE_SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Keeps the bore of a sealed tunnel dry.
     *
     * <p>Carving the bore to air is not enough. The carve writes air where rock used to be, so any water
     * cell just outside that new air - an aquifer pocket, a lake bed, or the sea under a seabed bore -
     * flows into the bore the moment the chunk ticks. The tunnel then reads as a tube with water hanging
     * in it that neither falls nor drains. So every water cell that touches the walkable volume through
     * the lining is replaced by the lining itself, and water already inside the bore is removed.
     *
     * <p>Portal sections get the drying half only: their mouth is meant to be open, and lining it would
     * wall off the entrance the player walks through.
     */
    static int[] sealBore(WorldGenLevel world, TunnelPlan plan) {
        if (world == null || plan == null || plan.interior().isEmpty()) return new int[]{0, 0};
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        boolean sealShell = !plan.portal();
        int roof = roofOffset();
        int dried = 0;
        int sealed = 0;
        for (BlockPos interior : plan.interior()) {
            int terrain = RoadConstructionSafety.terrainHeight(world, interior.getX(), interior.getZ());
            for (int dy = 1; dy <= roof; dy++) {
                BlockPos inside = interior.above(dy);
                if (isWaterCell(world, inside)) {
                    if (!isWallOwned(inside)) {
                        world.setBlock(inside, air, 3);
                        dried++;
                    }
                    continue;
                }
                if (!sealShell) continue;
                if (!world.getBlockState(inside).isAir()) continue;
                for (int[] side : BORE_SIDES) {
                    BlockPos shell = inside.offset(side[0], 0, side[1]);
                    if (!isWaterCell(world, shell)) continue;
                    if (RoadConstructionSafety.isProtectedTunnelVolume(world, shell, plan.road(),
                            world.getBlockState(shell), terrain) || isWallOwned(shell)) continue;
                    world.setBlock(shell, stone, 3);
                    sealed++;
                }
            }
        }
        if (dried > 0 || sealed > 0) {
            long total = DRIED_BORE_CELLS.addAndGet(dried) + SEALED_SHELL_CELLS.addAndGet(sealed);
            if (total <= 64 || total % 512 == 0) {
                BlockPos sample = plan.interior().get(plan.interior().size() / 2);
                LOGGER.info("RoadWeaver tunnel sealed bore dried={} shell={} at {},{} y={} total={}",
                        dried, sealed, sample.getX(), sample.getZ(), sample.getY(), total);
            }
        }
        return new int[]{dried, sealed};
    }

    private static boolean isWaterCell(WorldGenLevel world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    /** Every direct tunnel write goes through the same wall veto; planning is never consulted here. */
    private static boolean isWallOwned(BlockPos pos) {
        return pos != null && net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE,
                pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Whether open water sits directly above this column's roof line.
     *
     * <p>Scanned from one block above the roof upwards. The previous version started at the column's
     * terrain height, and for a water column that height is the water surface plus one - already above the
     * water - so the scan found nothing and the "water is not an opening" rule never applied to the one case
     * it was written for.
     */
    private static boolean isWaterAboveRoof(WorldGenLevel world, BlockPos column) {
        int from = column.getY() + roofOffset() + 1;
        // Far enough to reach the sea surface from a deep bore, and never past the build height.
        int to = Math.min(world.getMaxBuildHeight() - 1,
                Math.max(world.getSeaLevel() + 2, from + 128));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(column.getX(), from, column.getZ());
        for (int y = from; y <= to; y++) {
            cursor.setY(y);
            if (world.getBlockState(cursor).getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    static boolean shouldOpenPortalTransition(String reason, List<BlockPos> centers, int[] profile,
                                              int spanStart, int spanEnd, int segmentIndex) {
        return "insufficient-overburden".equals(reason)
                && hasNearbyPortalSection(centers, profile, spanStart, spanEnd, segmentIndex);
    }

    private static boolean shouldOpenPortalTransition(String reason, List<BlockPos> centers, int[] profile,
                                                      Span span, int segmentIndex) {
        return span != null && shouldOpenPortalTransition(
                reason, centers, profile, span.start(), span.end(), segmentIndex);
    }

    /** Geometry/load failures must not split a mountain tunnel; only hard
     *  structural protection (protected-*) is allowed to stop the bore. */
    private static boolean isContinuityOnlyFailure(String reason) {
        if (reason == null) return false;
        return reason.equals("insufficient-overburden")
                || reason.equals("insufficient-wall-overburden")
                || reason.startsWith("unavailable-")
                || reason.startsWith("invalid-");
    }

    private static boolean hasNearbyPortalSection(List<BlockPos> centers, int[] profile,
                                                  int spanStart, int spanEnd, int segmentIndex) {
        if (centers == null || profile == null || centers.size() != profile.length
                || segmentIndex < spanStart || segmentIndex > spanEnd) return false;
        int from = Math.max(spanStart, segmentIndex - PORTAL_TRANSITION_SEGMENTS);
        int to = Math.min(spanEnd, segmentIndex + PORTAL_TRANSITION_SEGMENTS);
        for (int index = from; index <= to; index++) {
            if (isPortalSection(centers, profile, index)) return true;
        }
        return false;
    }

    private static SafetyCheck portalSafe(WorldGenLevel world, RoadData road, PlannedSection section, SpanState state) {
        try {
            SafetyCheck roadbed = roadbedSafe(world, road, section);
            if (!roadbed.safe()) return roadbed;
            for (BlockPos column : section.interior()) {
                if (!columnAvailableWithoutLoading(world, column)) {
                    return SafetyCheck.failure("unavailable-loaded-portal-column", column);
                }
                int terrain = RoadConstructionSafety.terrainHeight(world, column.getX(), column.getZ());
                int clearTo = Math.min(MAX_PORTAL_CLEAR_HEIGHT,
                        Math.max(clearHeight(), terrain - column.getY() + 1));
                if (column.getY() < world.getMinBuildHeight()
                        || column.getY() + clearTo >= world.getMaxBuildHeight()) {
                    return SafetyCheck.failure("invalid-portal-height", column);
                }
                int fromDy = section.isRoadColumn(column) ? 0 : 1;
                for (int dy = fromDy; dy <= clearTo; dy++) {
                    BlockPos volume = column.above(dy);
                    if (!world.getBlockState(volume).getFluidState().isEmpty()
                            && !world.getBlockState(volume).getFluidState().is(FluidTags.WATER)) {
                        return SafetyCheck.failure("fluid-in-portal", volume);
                    }
                }
                BlockPos clearanceStart = section.isRoadColumn(column) ? column : column.above();
                int clearanceHeight = section.isRoadColumn(column) ? clearTo + 1 : clearTo;
                AboveColumnClearer.ClearanceCheck clearance = AboveColumnClearer.checkAboveColumnExact(
                        world, clearanceStart, road, clearanceHeight, true,
                        pos -> isOwnTunnelLantern(world, pos, road, state)
                                || isOwnLegacyPortalWall(world, pos, road, section));
                if (!clearance.ready()) return SafetyCheck.failure(clearance.reason(), clearance.position());
            }
            return SafetyCheck.success();
        } catch (RuntimeException unavailableWorldgenColumn) {
            return SafetyCheck.failure("unavailable-portal-column", section.lantern());
        }
    }

    private static SafetyCheck junctionSafe(WorldGenLevel world, RoadData road, PlannedSection section,
                                            SpanState state, boolean externalJunction) {
        try {
            SafetyCheck roadbed = roadbedSafe(world, road, section);
            if (!roadbed.safe()) return roadbed;
            for (BlockPos column : section.interior()) {
                if (!columnAvailableWithoutLoading(world, column)) {
                    return SafetyCheck.failure("unavailable-loaded-junction-column", column);
                }
                if (column.getY() < world.getMinBuildHeight()
                        || column.getY() + roofOffset() >= world.getMaxBuildHeight()) {
                    return SafetyCheck.failure("invalid-junction-height", column);
                }
                int fromDy = section.isRoadColumn(column) ? 0 : 1;
                for (int dy = fromDy; dy <= clearHeight(); dy++) {
                    BlockPos volume = column.above(dy);
                    if (!world.getBlockState(volume).getFluidState().isEmpty()
                            && !world.getBlockState(volume).getFluidState().is(FluidTags.WATER)) {
                        return SafetyCheck.failure("fluid-in-junction", volume);
                    }
                }
                BlockPos clearanceStart = section.isRoadColumn(column) ? column : column.above();
                int clearanceHeight = section.isRoadColumn(column) ? clearHeight() + 1 : clearHeight();
                AboveColumnClearer.ClearanceCheck clearance = AboveColumnClearer.checkAboveColumnExact(
                        world, clearanceStart, road, clearanceHeight, true,
                        pos -> isOwnTunnelLantern(world, pos, road, state)
                                || (externalJunction
                                && RoadConstructionSafety.isClearableExternalJunctionStructureBlock(
                                world, pos, road)));
                if (!clearance.ready()) return SafetyCheck.failure(clearance.reason(), clearance.position());
            }
            return SafetyCheck.success();
        } catch (RuntimeException unavailableWorldgenColumn) {
            return SafetyCheck.failure("unavailable-junction-column", section.lantern());
        }
    }

    private static SafetyCheck roadbedSafe(WorldGenLevel world, RoadData road, PlannedSection section) {
        for (BlockPos roadColumn : section.road()) {
            BlockPos roadbed = roadColumn.below();
            if (!columnAvailableWithoutLoading(world, roadbed)) {
                return SafetyCheck.failure("unavailable-loaded-roadbed", roadbed);
            }
            int terrain = RoadConstructionSafety.terrainHeight(world, roadbed.getX(), roadbed.getZ());
            if (RoadConstructionSafety.isProtectedTunnelVolume(world, roadbed, road,
                    world.getBlockState(roadbed), terrain)) {
                return SafetyCheck.failure("protected-roadbed", roadbed);
            }
        }
        return SafetyCheck.success();
    }

    private static boolean isProtectedTunnelBlock(WorldGenLevel world, RoadData road, BlockPos position,
            BlockState block, SpanState state, int columnTerrainY) {
        if (RoadConstructionSafety.isProtectedTunnelVolume(world, position, road, block, columnTerrainY)) {
            return !isOwnedTunnelLantern(block, position, state.plannedLanterns);
        }
        return false;
    }

    private static boolean isOwnTunnelLantern(WorldGenLevel world, BlockPos position, RoadData road, SpanState state) {
        return position != null && isOwnedTunnelLantern(
                        world.getBlockState(position), position, state.plannedLanterns)
                && !RoadConstructionSafety.isProtectedPreparedLocation(world, position, road);
    }

    private static boolean isOwnLegacyPortalWall(WorldGenLevel world, BlockPos position,
                                                  RoadData road, PlannedSection section) {
        if (world == null || position == null || section == null
                || !world.getBlockState(position).is(Blocks.STONE_BRICKS)
                || RoadConstructionSafety.isProtectedPreparedLocation(world, position, road)) return false;
        for (BlockPos wall : section.walls()) {
            if (wall == null || wall.getX() != position.getX() || wall.getZ() != position.getZ()) continue;
            int dy = position.getY() - wall.getY();
            if (dy >= 0 && dy <= 3) return true;
        }
        return false;
    }

    private static void clearLegacyPortalWalls(WorldGenLevel world, TunnelPlan plan) {
        for (BlockPos wall : plan.walls()) {
            for (int dy = 0; dy <= 3; dy++) {
                BlockPos position = wall.above(dy);
                BlockState state = world.getBlockState(position);
                if (!state.is(Blocks.STONE_BRICKS)
                        || RoadConstructionSafety.isProtectedPreparedLocation(world, position, plan.road())) continue;
                // 这里只认「石砖」就删 —— 长城的墙带正是 stone_bricks，所以必须否决长城列，
                // 否则任何贴着墙的隧道洞口都会在墙上开一条竖缝。
                if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                        net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CUT,
                        position.getX(), position.getY(), position.getZ())) continue;
                world.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    static boolean isOwnedTunnelLantern(BlockState block, BlockPos position, Set<Long> plannedLanterns) {
        return block != null && position != null && plannedLanterns != null
                && block.is(Blocks.LANTERN) && plannedLanterns.contains(position.asLong());
    }

    private static int terrainHeightAt(WorldGenLevel world, BlockPos pos) {
        return pos == null || world == null
                ? Integer.MIN_VALUE
                : RoadConstructionSafety.terrainHeight(world, pos.getX(), pos.getZ());
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
    private static boolean isHardProtectionFailure(String reason) {
        return "protected-block".equals(reason)
                || "protected-roadbed".equals(reason)
                // 0.9.027：AboveColumnClearer 会把它写成 `protected-headroom:<blockId>`，
                // 把挡住净空的方块名带进日志（N-10 的取证需求），所以这里必须按前缀判。
                || (reason != null && reason.startsWith("protected-headroom"));
    }

    /** {@code protected-headroom[:blockId]} —— 只否掉头顶净空，不否掉整站（见 preflight 里的 N-10 乙）。 */
    private static boolean isHeadroomOnly(String reason) {
        return reason != null && reason.startsWith("protected-headroom");
    }

    private static TunnelPlan protectedGapPlan(RoadData road, List<BlockPos> centers, Span span,
            int segmentIndex, String reason, BlockPos position) {
        long count = PROTECTED_GAP_SECTIONS.incrementAndGet();
        if (count <= 32 || count % 128 == 0) {
            BlockPos center = centers != null && segmentIndex >= 0 && segmentIndex < centers.size()
                    ? centers.get(segmentIndex) : null;
            LOGGER.warn("RoadWeaver tunnel PROTECTED_GAP road={}->{} segment={} span={}..{} reason={} center={} pos={} "
                            + "count={} action=skip_station span_stays_tunnel",
                    road == null ? RoadData.NO_OWNER_2D : road.ownerA2dKey(),
                    road == null ? RoadData.NO_OWNER_2D : road.ownerB2dKey(),
                    segmentIndex, span == null ? -1 : span.start(), span == null ? -1 : span.end(),
                    reason, center, position, count);
        }
        return TunnelPlan.protectedGapPlan();
    }

    private static TunnelPlan reject(WorldGenLevel world, RoadData road, List<BlockPos> centers, Span span,
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
            LOGGER.warn("RoadWeaver blocked tunnel section road={}->{} segment={} span={}..{} reason={} center={} pos={} count={}",
                    road == null ? RoadData.NO_OWNER_2D : road.ownerA2dKey(),
                    road == null ? RoadData.NO_OWNER_2D : road.ownerB2dKey(),
                    segmentIndex, span == null ? -1 : span.start(), span == null ? -1 : span.end(),
                    reason, center, position, count);
        }
        return TunnelPlan.blockedPlan();
    }

    /** Stable identity of one tunnel span of one road for the rejection ledger. */
    private static long spanKey(RoadData road, Span span) {
        long fingerprint = road == null ? 0L : net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage.computeFingerprint(road);
        int start = span == null ? -1 : span.start();
        int end = span == null ? -1 : span.end();
        return fingerprint * 1_000_003L + start * 31L + end;
    }

    /** 0.8.365 (S1): true when this span was already rejected for this level. */
    private static boolean isSpanRejected(net.minecraft.server.level.ServerLevel level, RoadData road, Span span) {
        if (level == null || road == null || span == null) return false;
        Set<Long> rejected;
        synchronized (REJECTED_TUNNEL_SPANS) { rejected = REJECTED_TUNNEL_SPANS.get(level); }
        return rejected != null && rejected.contains(spanKey(road, span));
    }

    /** 0.8.365 (S1): records the whole-span rejection for this level only. */
    private static void markSpanRejected(net.minecraft.server.level.ServerLevel level, RoadData road, Span span) {
        if (level == null || road == null || span == null) return;
        Set<Long> rejected;
        synchronized (REJECTED_TUNNEL_SPANS) {
            rejected = REJECTED_TUNNEL_SPANS.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet());
        }
        rejected.add(spanKey(road, span));
    }

    private static boolean containsWater(WorldGenLevel world, List<BlockPos> positions) {
        if (world == null || positions == null) return false;
        for (BlockPos position : positions) {
            if (position != null && world.getBlockState(position).getFluidState().is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** Longitudinal length of a plan's interior, for normalising the glass gradient. */
    private static double scenicSpanLength(TunnelPlan plan) {
        if (plan == null || plan.interior().size() < 2) return 0.0D;
        List<BlockPos> interior = plan.interior();
        double length = 0.0D;
        for (int i = 0; i + 1 < interior.size(); i++) {
            BlockPos a = interior.get(i);
            BlockPos b = interior.get(i + 1);
            length += Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        }
        return length;
    }

    /** How far along the span this column sits, as 0..1 along the centre line. */
    private static double scenicProgress(BlockPos column, BlockPos start, double spanLength) {
        if (column == null || start == null || spanLength <= 0.0D) return 0.0D;
        double along = Math.hypot(column.getX() - start.getX(), column.getZ() - start.getZ());
        return along / spanLength;
    }

    /**
     * Glass colour for one point along a glazed tunnel, as a round-trip gradient.
     *
     * <p>Delegates to the shared shell so a submerged bridge deck and a bored tunnel use the
     * same palette and the same fade.</p>
     *
     * @param progress 0 at the span start, 1 at its end; clamped
     */
    private static BlockState scenicGlassState(double progress) {
        return ScenicGlassShell.glass(progress);
    }

    private static void placeScenicDome(WorldGenLevel world, TunnelPlan plan,
                                        BlockPos spanStart, double spanLength) {
        if (world == null || plan == null) return;
        for (BlockPos wall : plan.walls()) {
            BlockPos nearest = nearestInterior(wall, plan.interior());
            if (nearest == null) continue;
            int dx = Integer.compare(wall.getX() - nearest.getX(), 0);
            int dz = Integer.compare(wall.getZ() - nearest.getZ(), 0);
            if (dx == 0 && dz == 0) {
                dx = 1;
            }
            // Colour follows the same longitudinal gradient as the roof, sampled at this
            // wall's own position so the vault fades along with the tube.
            BlockState glass = scenicGlassState(scenicProgress(wall, spanStart, spanLength));
            // A semicircular arch in the cross-section plane, shared with the submerged
            // deck builder so a glazed tunnel and a glazed crossing match.
            //
            // `outward` is the lateral distance from the wall, so the rise comes from the
            // circle `sqrt(r^2 - outward^2)` above the springing line. At outward 0 this
            // gives the crown (`DOME_SPRING + DOME_RADIUS`, matching the old flat peak) and
            // at outward `DOME_RADIUS` the springing line itself. Filling from the road level
            // up to that height keeps the full-height glazed side walls and only reshapes the
            // arch.
            for (int outward = 0; outward <= DOME_RADIUS; outward++) {
                BlockPos column = wall.offset(dx * outward, 0, dz * outward);
                int top = DOME_SPRING + ScenicGlassShell.archRise(outward);
                for (int dy = 0; dy <= top; dy++) {
                    BlockPos glassPos = column.above(dy);
                    if (isWallOwned(glassPos)
                            || RoadConstructionSafety.isProtectedExactConstructionBlock(world, glassPos, plan.road())) {
                        continue;
                    }
                    world.setBlock(glassPos, glass, 3);
                }
            }
        }
    }

    private static BlockPos nearestInterior(BlockPos target, List<BlockPos> interior) {
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

    /** Runtime backfill may inspect loaded chunks, but must never request a new one. */
    private static boolean columnAvailableWithoutLoading(WorldGenLevel world, BlockPos position) {
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

    /**
     * Adds the clearance and wall cells one lateral step out from a roadway column.
     *
     * <p>Axis-aligned routes step once along the normal. Diagonal routes step along both axes for the same
     * lateral step, because a diagonal normal cannot be expressed as a single cell and stepping one cell
     * along it would move two projection units (see the comment in {@link #plannedSection}).
     */
    private static void lateral(Map<Long, BlockPos> interior, Map<Long, BlockPos> walls,
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

    private static Span findSpan(List<RoadSpan> spans, List<BlockPos> centers, int index) {
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

    private static boolean isInSpan(List<RoadSpan> spans, List<BlockPos> centers, int index, SpanType type) {
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
    static int indexOf(List<BlockPos> centers, BlockPos target) {
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
                LOGGER.info("RoadWeaver tunnel span endpoint matched by nearest station: target={} nearest={} dxz={}",
                        target, centers.get(best), bestDist);
            }
            return best;
        }
        if (STATION_FALLBACK_MISSES.incrementAndGet() <= 8) {
            LOGGER.warn("RoadWeaver tunnel span endpoint unmatchable: target={} nearestDxz={} centers={} "
                    + "-> this tunnel span will be skipped entirely",
                    target, best < 0 ? -1 : bestDist, centers.size());
        }
        return -1;
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    private static Map<Long, BlockPos> unique(Iterable<BlockPos> positions) {
        Map<Long, BlockPos> result = new LinkedHashMap<>();
        for (BlockPos pos : positions) add(result, pos);
        return result;
    }

    private static void add(Map<Long, BlockPos> positions, BlockPos pos) {
        positions.putIfAbsent(pos.asLong(), pos);
    }

    private static SpanState spanState(net.minecraft.server.level.ServerLevel level, RoadData road,
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

    public record TunnelPlan(List<BlockPos> interior, List<BlockPos> walls, BlockPos lantern, boolean placeLantern,
            RoadData road, boolean portal, boolean junction, boolean scenic, boolean blocked,
            boolean protectedGap) {
        private static final TunnelPlan NONE =
                new TunnelPlan(List.of(), List.of(), null, false, null, false, false, false, false, false);
        private static final TunnelPlan BLOCKED =
                new TunnelPlan(List.of(), List.of(), null, false, null, false, false, false, true, false);
        private static final TunnelPlan PROTECTED_GAP =
                new TunnelPlan(List.of(), List.of(), null, false, null, false, false, false, true, true);
        public TunnelPlan { interior = List.copyOf(interior); walls = List.copyOf(walls); }
        public static TunnelPlan none() { return NONE; }
        public static TunnelPlan blockedPlan() { return BLOCKED; }
        public static TunnelPlan protectedGapPlan() { return PROTECTED_GAP; }
        public boolean active() { return !interior.isEmpty(); }
        public List<BlockPos> shell() { Map<Long, BlockPos> all = unique(interior); all.putAll(unique(walls)); return List.copyOf(all.values()); }
    }

    public static record PlannedSection(List<BlockPos> road,
                                 List<BlockPos> interior,
                                 List<BlockPos> walls,
                                 List<BlockPos> shell,
                                 BlockPos lantern) {
        boolean isRoadColumn(BlockPos column) {
            return column != null && road.contains(column);
        }
    }

    private record SafetyCheck(boolean safe, String reason, BlockPos position) {
        private static SafetyCheck success() { return new SafetyCheck(true, "ready", null); }
        private static SafetyCheck failure(String reason, BlockPos position) {
            return new SafetyCheck(false, reason, position);
        }
    }

    private record Span(int start, int end) {
        int length(List<BlockPos> centers) {
            double total = 0;
            for (int i = start; i < end; i++) total += Math.hypot(centers.get(i + 1).getX() - centers.get(i).getX(), centers.get(i + 1).getZ() - centers.get(i).getZ());
            return (int) Math.round(total);
        }

    }

    private record SpanKey(long first, long second, long ownerFirst, long ownerSecond) { }

    private enum Decision { UNKNOWN, APPROVED, REJECTED }

    private static final class SpanState {
        private final Object lock = new Object();
        private Decision decision = Decision.UNKNOWN;
        private final Set<Long> plannedLanterns = new HashSet<>();
        private boolean lanternsPrepared;
        /** 0.9.064：洞口段长判定 —— 每个 span 只算一次、只打一行（同一 span 的其它站复用）。 */
        private boolean portalResolved;
        private volatile PortalSplit portalSplit;

        private void preparePortal(WorldGenLevel world, RoadData road, List<BlockPos> centers,
                                   int[] profile, Span span) {
            if (portalResolved) return;
            portalResolved = true;
            portalSplit = announcePortalSplit(resolvePortalSplit(world, road, centers, profile, span));
        }

        private void prepareLanterns(RoadData road, List<BlockPos> centers, int[] profile, Span span) {
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
}
