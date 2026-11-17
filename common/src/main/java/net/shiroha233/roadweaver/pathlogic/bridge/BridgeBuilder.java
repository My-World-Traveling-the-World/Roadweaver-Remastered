/* 文件职责：沿路线造木桥 —— 不把模板地形向下投影，避免在桥下凭空造出一片地。
 *
 * 「模板地形不向下投影」是踩过的坑：桥模板自带地形块，
 * 无脑投影会在河床上堆出一根根柱子。
 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.decoration.ScenicGlassShell;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LanternBlock;
import net.shiroha233.roadweaver.util.Line;

/** 沿既有路线程序化建木桥，不把模板外围地形向下投影成填土。 */
public final class BridgeBuilder {
    private static final int BODY_PERIOD = 7;
    private static final int SUPPORT_PHASE = 3;

    private BridgeBuilder() {
    }

    public static boolean placeSegment(
            WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            BlockPos middle,
            BlockPos prev,
            BlockPos next,
            int roadWidth,
            int deckSurfaceY,
            int bridgePhase,
            int segmentIndex,
            RandomSource random,
            ModConfig cfg,
            boolean placePier,
            boolean placeRail,
            BridgeMaterialPalette palette
    ) {
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.BRIDGE, middle.getX(), middle.getY(), middle.getZ())) return false;
        return placeSegment(world, road, seg, middle, prev, next, roadWidth, deckSurfaceY, bridgePhase,
                segmentIndex, random, cfg, placePier, placeRail, palette, null);
    }

    public static boolean placeSegment(
            WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            BlockPos middle,
            BlockPos prev,
            BlockPos next,
            int roadWidth,
            int deckSurfaceY,
            int bridgePhase,
            int segmentIndex,
            RandomSource random,
            ModConfig cfg,
            boolean placePier,
            boolean placeRail,
            BridgeMaterialPalette palette,
            Line geometryLine
    ) {
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.BRIDGE, middle.getX(), middle.getY(), middle.getZ())) return false;
        if (geometryLine != null && geometryLine.isCurved()) {
            seg = new RoadSegmentPlacement(
                    geometryLine.mapRoadPosition(seg.middlePos()),
                    geometryLine.mapRoadPositions(seg.positions()));
            middle = geometryLine.mapRoadPosition(middle);
            prev = geometryLine.mapRoadPosition(prev);
            next = geometryLine.mapRoadPosition(next);
        }
        int deckBlockY = deckSurfaceY - 1;
        int[] edgeRange = edgeProjectionRange(seg, prev, next);

        // 0.9.005：桥面净空按「隧道净空」取（默认 5，可用 bridge.clearHeight 覆盖）。
        int headroom = AboveColumnClearer.bridgeHeadroom(cfg);
        boolean forced = cfg == null || cfg.roadAppearance().forcedClearProtected();
        int sideClear = cfg == null ? 0 : cfg.bridge().sideClear();
        java.util.Set<Long> clearedSideColumns = new java.util.HashSet<>();

        for (BlockPos widthPos : seg.positions()) {
            BlockPos deckPos = new BlockPos(widthPos.getX(), deckBlockY, widthPos.getZ());
            if (RoadConstructionSafety.isProtectedExactConstructionBlock(world, deckPos, road)) {
                return false;
            }
            // 保底档（forced）不再因「受保护方块」整段拒建 —— 改到下面强行清。否则山里
            // 只要压着巨大树预留区/结构盒，整座桥就消失，只剩地形挡路（真机 180 次）。
            if (!forced) {
                AboveColumnClearer.ClearanceCheck clearance = AboveColumnClearer.checkAboveColumn(
                        world, deckPos.above(), road, headroom, true);
                if (!clearance.ready()) return false;
            }
        }

        for (BlockPos widthPos : seg.positions()) {
            int projection = edgeProjection(widthPos, prev, next);
            int widthSlot = widthSlot(projection, edgeRange);
            BlockPos deckPos = new BlockPos(widthPos.getX(), deckBlockY, widthPos.getZ());
            // The wall owns this column: no deck block, no clearance. This is per target, not the
            // pre-remap middle lookup that used to miss curved bridges at the wall.
            if (isWallOwned(deckPos)) continue;
            world.setBlock(deckPos, deckState(bridgePhase, widthSlot, palette), 3);
            if (forced) {
                AboveColumnClearer.clearForcedHeadroom(
                        world, deckPos.above(), road, headroom, true, "bridge-deck");
                // 侧向 ±N：只清 [桥面, 桥面+headroom) 这一竖向带，向下不动 —— 修的是"贴着桥边
                // 凸出来的草/石台阶"，不会把两侧挖成沟。
                // 0.9.005-C：相邻 deck 位置的侧向圆环会重叠，同一列被反复清（重复 heightmap 读 +
                // 重复 setBlock）。用 (x,z) 去重，一个 deck 段内每列只处理一次。
                for (int dx = -sideClear; dx <= sideClear; dx++) {
                    for (int dz = -sideClear; dz <= sideClear; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        BlockPos sideColumn =
                                new BlockPos(deckPos.getX() + dx, deckPos.getY(), deckPos.getZ() + dz);
                        if (!clearedSideColumns.add(sideColumn.asLong())) continue;
                        AboveColumnClearer.clearForcedHeadroom(world, sideColumn,
                                road, headroom, true, "bridge-side");
                    }
                }
            } else {
                // 桥面自己不做凸起削平（forced 分支已做过）⇒ flattenBumps=false，只清上方净空。
                AboveColumnClearer.clearAboveColumn(
                        world, deckPos.above(), cfg, road, headroom, true, false);
            }
        }

        placeCenterLightBlock(world, road, seg, middle, prev, next, deckBlockY, segmentIndex, geometryLine);

        placeTemplateBodySupports(world, road, seg, prev, next, deckBlockY, bridgePhase, palette.support());

        // A submerged deck becomes a glazed corridor instead of an open bridge.
        //
        // The deck elevation is unchanged: `BridgeDeckProfile` already interpolates between
        // the two landing heights and applies approach ramps, which is exactly the profile an
        // underwater run wants (one smooth grade between the two tunnel mouths, independent
        // of how the seabed rises and falls beneath it). What changes is the dressing — no
        // piers, no open railings, and a glass vault, so the crossing reads as a continuous
        // viewing tube rather than a bridge that happens to be underwater.
        if (isSubmerged(world, seg, deckBlockY)) {
            placeScenicCorridor(world, road, seg, prev, next, deckBlockY, middle, geometryLine,
                    cfg.roadClearHeight());
            return true;
        }

        if (placeRail) {
            BlockState rail = Math.floorMod(bridgePhase, BODY_PERIOD) == SUPPORT_PHASE
                    ? palette.accentRail()
                    : palette.rail();
            placeRails(world, road, seg, middle, prev, next, deckBlockY, rail);
        }

        if (placePier && BridgePierPolicy.isEnabled(cfg.bridgePierMaxHeight())) {
            int interval = Math.max(3, cfg.bridgePierInterval());
            if (segmentIndex % interval == 0) {
                int pierWidth = RoadConstructionSafety.isWatercourse(world, middle) ? 1 : cfg.bridgePierWidth();
                placePierUnder(
                        world,
                        road,
                        middle.getX(),
                        middle.getZ(),
                        deckBlockY - 1,
                        cfg.bridgePierMaxHeight(),
                        pierWidth,
                        withAxis(palette.support(), Direction.Axis.Y));
            }
        }
        return true;
    }

    /** 在桥面及其上方检查水体，识别淹没或排水空腔中的桥站，避免水下走廊两端开口。
 *
     * Whether this deck station sits under water rather than in open air.
     *
     * <p>Sampled at the deck itself and just above it. A station whose own block is already
     * wet is clearly submerged; a station in a drained pocket under a flooded column is
     * treated as submerged too, because the vault is what keeps the sea out. Checking only
     * the deck block would leave the first and last stations of a crossing open, and checking
     * too high would glaze a deck that merely passes under an arch.</p>
     */
    private static boolean isSubmerged(WorldGenLevel world, RoadSegmentPlacement seg, int deckBlockY) {
        if (world == null || seg == null || seg.positions() == null) return false;
        for (BlockPos pos : seg.positions()) {
            for (int dy = 0; dy <= ScenicGlassShell.RADIUS; dy++) {
                BlockPos at = new BlockPos(pos.getX(), deckBlockY + dy, pos.getZ());
                if (world.getBlockState(at).getFluidState().is(FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    /** 水下桥面使用共用半圆玻璃壳与灯光；刻意不加桥墩，保持悬挂水廊视野。
 *
     * Seals a submerged deck under a semicircular glass vault with periodic lighting.
     *
     * <p>Piers are deliberately omitted: the corridor hangs in the water column, so the
     * player sees the seabed through the floor gap and the ocean through the walls. The vault
     * springs from the deck edges and rises {@link ScenicGlassShell#RADIUS} at the crown,
     * matching the cross-section a glazed tunnel uses, so a tunnel that opens onto this
     * crossing continues without a change of profile.</p>
     */
    private static void placeScenicCorridor(WorldGenLevel world,
                                            RoadData road,
                                            RoadSegmentPlacement seg,
                                            BlockPos prev,
                                            BlockPos next,
                                            int deckBlockY,
                                            BlockPos middle,
                                            Line geometryLine,
                                            int clearHeight) {
        int[] range = edgeProjectionRange(seg, prev, next);
        double progress = scenicProgress(geometryLine, middle);
        BlockState glass = ScenicGlassShell.glass(progress);
        // Expand along the local deck normal, not world X. A Z-aligned bridge otherwise builds
        // its glass shell through the walkable interior.
        double dx = next.getX() - prev.getX();
        double dz = next.getZ() - prev.getZ();
        double length = Math.hypot(dx, dz);
        double nx = length > 1.0E-6D ? -dz / length : 1.0D;
        double nz = length > 1.0E-6D ? dx / length : 0.0D;
        // The deck columns are the driving surface. Glass may span above their clear height, but
        // never occupy a block inside it: the old loop built a second arch from every deck column,
        // so centre columns grew glass ribs through the carriageway.
        var deckColumns = new java.util.HashSet<Long>();
        for (BlockPos pos : seg.positions()) {
            deckColumns.add(columnKey(pos.getX(), pos.getZ()));
        }
        int roofRise = Math.max(ScenicGlassShell.RADIUS + 1, clearHeight + 1);
        for (BlockPos pos : seg.positions()) {
            int projection = edgeProjection(pos, prev, next);
            // Lateral offset from the centre line, so the vault is centred on the deck.
            int lateral = 0;
            if (range[1] > range[0]) {
                lateral = (int) Math.round(
                        (projection - (range[0] + range[1]) / 2.0D) * ScenicGlassShell.RADIUS
                                / ((range[1] - range[0]) / 2.0D));
            }
            for (int outward = 1; outward <= ScenicGlassShell.RADIUS; outward++) {
                int rise = ScenicGlassShell.archRise(outward);
                if (rise < 0) break;
                for (int side = -1; side <= 1; side += 2) {
                    int ox = (int) Math.round(nx * side * outward);
                    int oz = (int) Math.round(nz * side * outward);
                    BlockPos column = new BlockPos(pos.getX() + ox, deckBlockY, pos.getZ() + oz);
                    for (int dy = 1; dy <= Math.max(rise, clearHeight); dy++) {
                        BlockPos at = column.above(dy);
                        if (deckColumns.contains(columnKey(at.getX(), at.getZ()))
                                && dy <= clearHeight) {
                            continue;
                        }
                        if (RoadConstructionSafety.isProtectedExactConstructionBlock(world, at, road)
                                || isWallOwned(at)) {
                            continue;
                        }
                        world.setBlock(at, glass, 3);
                    }
                }
            }
            // The crown row closes the top of the arch across the full width.
            if (Math.abs(lateral) <= ScenicGlassShell.RADIUS) {
                int rise = ScenicGlassShell.archRise(lateral);
                if (rise >= 0) {
                    int crownY = deckBlockY + Math.max(rise + 1, roofRise);
                    BlockPos at = new BlockPos(pos.getX(), crownY, pos.getZ());
                    if (deckColumns.contains(columnKey(at.getX(), at.getZ()))
                            && crownY - deckBlockY <= clearHeight) {
                        continue;
                    }
                    if (!RoadConstructionSafety.isProtectedExactConstructionBlock(world, at, road)
                            && !isWallOwned(at)) {
                        world.setBlock(at, glass, 3);
                    }
                }
            }
        }
        placeCorridorLights(world, road, seg, deckBlockY, middle, geometryLine, clearHeight);
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** 沿跨越进度计算共用玻璃渐变，避免各区块各自重新起色。 */
    private static double scenicProgress(Line geometryLine, BlockPos middle) {
        if (geometryLine == null || middle == null) return 0.0D;
        double length = geometryLine.getTotalLength();
        if (!(length > 0.0D)) return 0.0D;
        Line.Frame frame = geometryLine.getFrame(new Vec3(middle.getX() + 0.5D,
                middle.getY() + 0.5D, middle.getZ() + 0.5D));
        return Math.max(0.0D, Math.min(1.0D, frame.globalT));
    }

    /** 灯从圆拱中心悬挂，复用桥灯间隔，保持水上与水下节奏一致。
 *
     * Hanging lights along the corridor centre, on the same interval the bridge uses.
     *
     * <p>Reuses the bridge light spacing so a crossing does not change rhythm when it becomes
     * submerged, and hangs each lantern from the crown so it lights the tube from above.</p>
     */
    private static void placeCorridorLights(WorldGenLevel world,
                                            RoadData road,
                                            RoadSegmentPlacement seg,
                                            int deckBlockY,
                                            BlockPos middle,
                                            Line geometryLine,
                                            int clearHeight) {
        int interval = RoadConstants.DEFAULT_BRIDGE_LIGHT_INTERVAL_BLOCKS;
        if (interval <= 0) return;
        double distance;
        if (geometryLine != null) {
            double length = geometryLine.getTotalLength();
            if (!(length > 0.0D)) return;
            Line.Frame frame = geometryLine.getFrame(new Vec3(middle.getX() + 0.5D, deckBlockY,
                    middle.getZ() + 0.5D));
            distance = Math.max(0.0D, Math.min(length, frame.globalT * length));
        } else {
            distance = Math.abs(middle.getX()) + Math.abs(middle.getZ());
        }
        // Half-open interval, so exactly one station claims each spacing window.
        if (Math.floor(distance / interval) == Math.floor((distance - 1.0D) / interval)) return;
        BlockPos crown = new BlockPos(middle.getX(),
                deckBlockY + Math.max(ScenicGlassShell.RADIUS, clearHeight + 1), middle.getZ());
        if (RoadConstructionSafety.isProtectedExactConstructionBlock(world, crown, road)
                || isWallOwned(crown)) return;
        world.setBlock(crown,
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 3);
    }

    private static void placeRails(
            WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            BlockPos middle,
            BlockPos prev,
            BlockPos next,
            int deckBlockY,
            BlockState rail
    ) {
        int[] range = edgeProjectionRange(seg, prev, next);
        if (range[0] == range[1]) {
            return;
        }
        BlockState connectedRail = withRailConnections(rail, middle, prev, next);
        for (BlockPos pos : seg.positions()) {
            int projection = edgeProjection(pos, prev, next);
            if (projection != range[0] && projection != range[1]) {
                continue;
            }
            BlockPos railPos = new BlockPos(pos.getX(), deckBlockY + 1, pos.getZ());
            if (!RoadConstructionSafety.isProtectedExactConstructionBlock(world, railPos, road)
                    && !isWallOwned(railPos)) {
                world.setBlock(railPos, connectedRail, 3);
            }
        }
    }

    /** 按稳定路线里程替换桥面中心灯，避免区块顺序改变灯位。 */
    private static void placeCenterLightBlock(
            WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            BlockPos middle,
            BlockPos prev,
            BlockPos next,
            int deckBlockY,
            int segmentIndex,
            Line geometryLine
    ) {
        int interval = RoadConstants.DEFAULT_BRIDGE_LIGHT_INTERVAL_BLOCKS;
        if (interval <= 0) return;
        if (geometryLine != null) {
            double length = geometryLine.getTotalLength();
            if (!(length > 0.0D)) return;
            Line.Frame frame = geometryLine.getFrame(new Vec3(middle.getX() + 0.5D, deckBlockY,
                    middle.getZ() + 0.5D));
            double distance = Math.max(0.0D, Math.min(length, frame.globalT * length));
            // Claim the span interval this station owns instead of matching
            // one multiple of the interval: stations are about one block
            // apart, so each station covers [distance - half,
            // distance + half] and every 16-block span point across the whole
            // bridge is claimed by exactly one station. Matching a single
            // multiple lit short bridges only at their two landings.
            double half = stationSpacingBlocks(middle, prev, next) * 0.5D;
            long lowest = (long) Math.ceil((distance - half) / interval);
            long highest = (long) Math.floor((distance + half) / interval);
            if (lowest > highest) return;
        } else if (Math.floorMod(segmentIndex, interval) != 0) {
            // Legacy callers do not provide the complete bridge line. Keep a
            // deterministic block-scale fallback instead of the old every-2
            // segment rule.
            return;
        }
        int[] range = edgeProjectionRange(seg, prev, next);
        BlockPos center = null;
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos pos : seg.positions()) {
            if (widthSlot(edgeProjection(pos, prev, next), range) != 3) continue;
            double dx = pos.getX() - middle.getX();
            double dz = pos.getZ() - middle.getZ();
            double distance = dx * dx + dz * dz;
            if (distance < best) {
                best = distance;
                center = pos;
            }
        }
        if (center == null) return;
        BlockPos lightPos = new BlockPos(center.getX(), deckBlockY, center.getZ());
        if (RoadConstructionSafety.isProtectedExactConstructionBlock(world, lightPos, road)) return;
        if (isWallOwned(lightPos)) return;
        if (!world.getBlockState(lightPos).getFluidState().isEmpty()) return;
        world.setBlock(lightPos, Blocks.SEA_LANTERN.defaultBlockState(), 3);
    }

    /** 站点拥有邻站中点之间的里程窗口，避免相邻站同时认领同一灯位。
 *
     * Local station spacing in blocks. A station owns the interval between the
     * midpoints to its neighbours, so the claim window is half of the distance
     * to the nearest neighbour. prev/next are two stations away.
     */
    private static double stationSpacingBlocks(BlockPos middle, BlockPos prev, BlockPos next) {
        double forward = horizontalDistance(middle, next);
        double backward = horizontalDistance(middle, prev);
        double spacing = Double.MAX_VALUE;
        if (forward > 0.0D) spacing = Math.min(spacing, forward);
        if (backward > 0.0D) spacing = Math.min(spacing, backward);
        if (spacing == Double.MAX_VALUE) return 1.0D;
        // prev/next are +-2 stations, and a station is never closer than one
        // block to the next one in the persisted route.
        return Math.max(1.0D, spacing * 0.5D);
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        if (first == null || second == null) return 0.0D;
        double dx = second.getX() - first.getX();
        double dz = second.getZ() - first.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    static BlockState deckState(int bridgePhase, int widthSlot, BridgeMaterialPalette palette) {
        if (widthSlot == 0 || widthSlot == 6) {
            return palette.edgeDeck();
        }
        int phase = Math.floorMod(bridgePhase, BODY_PERIOD);
        if (phase == SUPPORT_PHASE) {
            return widthSlot == 2 || widthSlot == 4 ? palette.accentDeck() : palette.deck();
        }
        return widthSlot == 3 ? palette.accentDeck() : palette.deck();
    }

    private static void placeTemplateBodySupports(
            WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            BlockPos prev,
            BlockPos next,
            int deckBlockY,
            int bridgePhase,
            BlockState support
    ) {
        if (Math.floorMod(bridgePhase, BODY_PERIOD) != SUPPORT_PHASE) {
            return;
        }
        int[] range = edgeProjectionRange(seg, prev, next);
        BlockState verticalSupport = withAxis(support, Direction.Axis.Y);
        for (BlockPos pos : seg.positions()) {
            int slot = widthSlot(edgeProjection(pos, prev, next), range);
            if (slot != 1 && slot != 4) {
                continue;
            }
            for (int depth = 1; depth <= 3; depth++) {
                BlockPos supportPos = new BlockPos(pos.getX(), deckBlockY - depth, pos.getZ());
                BlockState existing = world.getBlockState(supportPos);
                if (!existing.isAir() && existing.getFluidState().isEmpty()) {
                    break;
                }
                if (RoadConstructionSafety.isProtectedExactConstructionBlock(world, supportPos, road)
                        || isWallOwned(supportPos)) {
                    break;
                }
                world.setBlock(supportPos, verticalSupport, 3);
            }
        }
    }

    static int widthSlot(int projection, int[] range) {
        if (range[0] >= range[1]) {
            return 3;
        }
        double normalized = (projection - range[0]) / (double) (range[1] - range[0]);
        return Math.max(0, Math.min(6, (int) Math.round(normalized * 6.0)));
    }

    private static boolean isWallOwned(BlockPos pos) {
        return pos != null && net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE,
                pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockState withRailConnections(
            BlockState rail,
            BlockPos middle,
            BlockPos prev,
            BlockPos next
    ) {
        BlockState state = rail;
        if (state.hasProperty(CrossCollisionBlock.NORTH)) state = state.setValue(CrossCollisionBlock.NORTH, false);
        if (state.hasProperty(CrossCollisionBlock.EAST)) state = state.setValue(CrossCollisionBlock.EAST, false);
        if (state.hasProperty(CrossCollisionBlock.SOUTH)) state = state.setValue(CrossCollisionBlock.SOUTH, false);
        if (state.hasProperty(CrossCollisionBlock.WEST)) state = state.setValue(CrossCollisionBlock.WEST, false);
        state = connectToward(state, middle, prev);
        return connectToward(state, middle, next);
    }

    private static BlockState connectToward(BlockState state, BlockPos from, BlockPos toward) {
        int dx = toward.getX() - from.getX();
        int dz = toward.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return state;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (dx > 0 && state.hasProperty(CrossCollisionBlock.EAST)) {
                return state.setValue(CrossCollisionBlock.EAST, true);
            }
            if (dx < 0 && state.hasProperty(CrossCollisionBlock.WEST)) {
                return state.setValue(CrossCollisionBlock.WEST, true);
            }
        }
        if (dz > 0 && state.hasProperty(CrossCollisionBlock.SOUTH)) {
            return state.setValue(CrossCollisionBlock.SOUTH, true);
        }
        if (dz < 0 && state.hasProperty(CrossCollisionBlock.NORTH)) {
            return state.setValue(CrossCollisionBlock.NORTH, true);
        }
        return state;
    }

    private static void placePierUnder(
            WorldGenLevel world,
            RoadData road,
            int x,
            int z,
            int fromY,
            int maxHeight,
            int pierWidth,
            BlockState pier
    ) {
        int minY = world.getMinBuildHeight();
        int half = Math.max(0, pierWidth - 1);
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int y = fromY;
                int placed = 0;
                while (y >= minY && placed < maxHeight) {
                    BlockPos cur = new BlockPos(x + dx, y, z + dz);
                    if (world.getBlockState(cur).isFaceSturdy(world, cur, Direction.UP)) {
                        break;
                    }
                    if (RoadConstructionSafety.isProtectedExactConstructionBlock(world, cur, road)
                            || isWallOwned(cur)) {
                        break;
                    }
                    world.setBlock(cur, pier, 3);
                    y--;
                    placed++;
                }
            }
        }
    }

    private static int[] edgeProjectionRange(RoadSegmentPlacement seg, BlockPos prev, BlockPos next) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (BlockPos pos : seg.positions()) {
            int projection = edgeProjection(pos, prev, next);
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }
        return new int[]{min, max};
    }

    private static int edgeProjection(BlockPos pos, BlockPos prev, BlockPos next) {
        // Project pos onto the axis perpendicular to the road direction. The old code reduced
        // dx/dz to their sign (-1/0/1), which pinned the normal to a fixed 45 degree direction
        // for any diagonal road, misclassifying width slots and misplacing deck, piers and rails.
        // Use the true unit normal and scale by 256 so widthSlot keeps fixed-point precision.
        double dx = next.getX() - prev.getX();
        double dz = next.getZ() - prev.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return 0;
        double normalX = -dz / len;
        double normalZ = dx / len;
        return (int) Math.round((pos.getX() * normalX + pos.getZ() * normalZ) * 256.0);
    }

    private static BlockState withAxis(BlockState state, Direction.Axis axis) {
        if (state.hasProperty(RotatedPillarBlock.AXIS)) {
            return state.setValue(RotatedPillarBlock.AXIS, axis);
        }
        return state;
    }
}
