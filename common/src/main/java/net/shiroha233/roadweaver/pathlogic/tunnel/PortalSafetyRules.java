/* 文件职责：洞口安全规则 —— 什么时候必须开口、什么时候可以封洞。 */
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

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.ScenicDomeBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.PlannedSectionBuilder.*;

/**
 * 洞口安全规则。
 *
 * <h2>核心矛盾</h2>
 * <p>隧道两端必须能走进去（要有洞口），但**洞口开在哪里是有代价的**：
 * 开在山体侧面会破坏山形，开在水下会灌水。所以规则分两半：
 * 「必须开」（{@code requiresOpenPortal}）与「能不能开」
 * （{@code portalSafe} / {@code junctionSafe} / {@code roadbedSafe}）。</p>
 *
 * <h2>★ 封洞（sealBore）是兜底，不是常态</h2>
 * <p>万一段落的洞口位置怎么都算不安全，宁可把这一段封成实心隧道
 * （玩家走不过去但地形完整），也不许在山体上开一个大洞。
 * 这条和「修不动就原样退回」是同一族纪律：**宁可少做，不许做坏**。</p>
 *
 * <h2>★ 水面以上也算水</h2>
 * <p>{@code isWaterAboveRoof} —— 洞顶上方有水，即使洞身本身是干的,
 * 挖穿洞顶也会灌进来。只判洞身高度会漏掉这一类。</p>
 *
 * <p>本类是从 1521 行的 {@code RoadTunnelBuilder} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
final class PortalSafetyRules {
    static boolean requiresOpenPortal(WorldGenLevel world, PlannedSection section) {
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
    static final AtomicLong DRIED_BORE_CELLS = new AtomicLong();
    /** Water cells in the shell that the lining replaced. */
    static final AtomicLong SEALED_SHELL_CELLS = new AtomicLong();
    static final int[][] BORE_SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

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

    static boolean isWaterCell(WorldGenLevel world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    /** Every direct tunnel write goes through the same wall veto; planning is never consulted here. */
    static boolean isWallOwned(BlockPos pos) {
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
    static boolean isWaterAboveRoof(WorldGenLevel world, BlockPos column) {
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

    static boolean shouldOpenPortalTransition(String reason, List<BlockPos> centers, int[] profile,
                                                      Span span, int segmentIndex) {
        return span != null && shouldOpenPortalTransition(
                reason, centers, profile, span.start(), span.end(), segmentIndex);
    }

    /** Geometry/load failures must not split a mountain tunnel; only hard
     *  structural protection (protected-*) is allowed to stop the bore. */
    static boolean isContinuityOnlyFailure(String reason) {
        if (reason == null) return false;
        return reason.equals("insufficient-overburden")
                || reason.equals("insufficient-wall-overburden")
                || reason.startsWith("unavailable-")
                || reason.startsWith("invalid-");
    }

    static boolean hasNearbyPortalSection(List<BlockPos> centers, int[] profile,
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

    static SafetyCheck portalSafe(WorldGenLevel world, RoadData road, PlannedSection section, SpanState state) {
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

    static SafetyCheck junctionSafe(WorldGenLevel world, RoadData road, PlannedSection section,
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

    static SafetyCheck roadbedSafe(WorldGenLevel world, RoadData road, PlannedSection section) {
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

    static boolean isProtectedTunnelBlock(WorldGenLevel world, RoadData road, BlockPos position,
            BlockState block, SpanState state, int columnTerrainY) {
        if (RoadConstructionSafety.isProtectedTunnelVolume(world, position, road, block, columnTerrainY)) {
            return !isOwnedTunnelLantern(block, position, state.plannedLanterns);
        }
        return false;
    }

    static boolean isOwnTunnelLantern(WorldGenLevel world, BlockPos position, RoadData road, SpanState state) {
        return position != null && isOwnedTunnelLantern(
                        world.getBlockState(position), position, state.plannedLanterns)
                && !RoadConstructionSafety.isProtectedPreparedLocation(world, position, road);
    }

    static boolean isOwnLegacyPortalWall(WorldGenLevel world, BlockPos position,
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

    static void clearLegacyPortalWalls(WorldGenLevel world, TunnelPlan plan) {
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
}
