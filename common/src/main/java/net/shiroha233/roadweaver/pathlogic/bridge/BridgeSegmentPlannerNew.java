/* 文件职责：沿长跨水段铺设内置桥模板 —— 越界的模板坐标不向下投影，只写当前区块自己的方块。
 *
 * 「只写当前区块拥有的方块」是多区块并发生成的安全边界：
 * 跨区块写入会和另一个正在生成的区块抢同一列。
 */
package net.shiroha233.roadweaver.pathlogic.bridge;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.structures.registry.BridgeTemplateStructureRegistry;
import net.shiroha233.roadweaver.structures.registry.BridgeTemplateStructureRegistry.BridgeTemplate;
import net.shiroha233.roadweaver.util.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 模板桥仅投影已保存体素，并且每次只写当前区块拥有的方块，避免跨区块重复放置。
 *
 * Places the bundled RoadWeaver bridge template along a long water span.
 * Template coordinates outside the saved voxel volume are never projected
 * downward, and each invocation writes only blocks owned by the chunk that is
 * currently being generated.
 */
public final class BridgeSegmentPlannerNew {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Set<String> LOGGED_BRIDGES = ConcurrentHashMap.newKeySet();
    /** 诊断模板桥新增支撑列数量，不以计数决定建造行为。 */
    private static final java.util.concurrent.atomic.AtomicLong PIERS_PLACED =
            new java.util.concurrent.atomic.AtomicLong();
    /** 0.9.033：陆侧桥口靠桥身映射补出甲板的次数（O-14「陆侧桥口缺甲板」的取证计数）。 */
    private static final java.util.concurrent.atomic.AtomicLong LANDING_MOUTHS_FILLED =
            new java.util.concurrent.atomic.AtomicLong();

    /** 返回本进程实际新增的模板桥支撑列数，不能当作某一条桥的完工证明。 */
    public static long piersPlaced() {
        return PIERS_PLACED.get();
    }

    /** 统计模板桥陆侧口部的体素补齐，便于定位入口缺块。 */
    public static long landingMouthsFilled() {
        return LANDING_MOUTHS_FILLED.get();
    }

    // 规划和施工共用模板与水线读口，才能离线复现桥口映射而不复制几何。
    interface PlanningAccess {
        BridgeTemplate template(int seed);
        boolean isOpenWater(BlockPos pos);
    }

    interface PlacementAccess extends PlanningAccess {
        String dimensionId();
        boolean isProtected(BlockPos pos, RoadData road);
        void clearHeadroom(RoadSegmentPlacement segment, int deckY, RoadData road);
        int minBuildHeight();
        BlockState getBlockState(BlockPos pos);
        boolean isFullBlock(BlockState state, BlockPos pos);
        BlockState updateFromNeighbourShapes(BlockState state, BlockPos pos);
        void setBlock(BlockPos pos, BlockState state);
    }

    private record WorldPlacementAccess(WorldGenLevel world) implements PlacementAccess {
        public BridgeTemplate template(int seed) {
            return BridgeTemplateStructureRegistry.chooseOriginal(world.getLevel(), seed);
        }

        public boolean isOpenWater(BlockPos pos) {
            return isOpenWaterColumn(world, pos);
        }

        public String dimensionId() {
            return world.getLevel().dimension().location().toString();
        }

        public boolean isProtected(BlockPos pos, RoadData road) {
            return RoadConstructionSafety.isProtectedExactConstructionBlock(world, pos, road);
        }

        public void clearHeadroom(RoadSegmentPlacement segment, int deckY, RoadData road) {
            var config = ConfigService.get();
            if (config.roadAppearance().forcedClearProtected()) {
                net.shiroha233.roadweaver.decoration.system.AboveColumnClearer
                        .clearTemplateBridgeHeadroom(world, segment, deckY, road, config);
            }
        }

        public int minBuildHeight() {
            return world.getMinBuildHeight();
        }

        public BlockState getBlockState(BlockPos pos) {
            return world.getBlockState(pos);
        }

        public boolean isFullBlock(BlockState state, BlockPos pos) {
            return state.isCollisionShapeFullBlock(world, pos);
        }

        public BlockState updateFromNeighbourShapes(BlockState state, BlockPos pos) {
            return Block.updateFromNeighbourShapes(state, world, pos);
        }

        public void setBlock(BlockPos pos, BlockState state) {
            world.setBlock(pos, state, 3);
        }
    }

    private BridgeSegmentPlannerNew() {
    }

    public static boolean processSegment(
            WorldGenLevel world,
            ChunkPos currentChunk,
            RoadData road,
            Line line,
            RoadSegmentPlacement segment,
            BlockPos middle,
            BlockPos previous,
            int deckY,
            boolean landingStation) {
        return processSegment(new WorldPlacementAccess(world), currentChunk, road, line,
                segment, middle, previous, deckY, landingStation);
    }

    static boolean processSegment(
            PlacementAccess access,
            ChunkPos currentChunk,
            RoadData road,
            Line line,
            RoadSegmentPlacement segment,
            BlockPos middle,
            BlockPos previous,
            int deckY,
            boolean landingStation) {
        double bridgeLength = line.getTotalLength();
        BridgeTemplate bridge = access.template(stableBridgeSeed(line));
        if (bridge == null) {
            return false;
        }
        String logKey = access.dimensionId() + "|" + Math.round(bridgeLength)
                + "|" + bridge.getId();
        if (LOGGED_BRIDGES.add(logKey)) {
            LOGGER.info("【本段选用的原始桥模板及桥面高度】 " + "RoadWeaver original bridge template={} lengthBlocks={} deckY={}",
                    bridge.getId(), Math.round(bridgeLength), deckY);
        }

        int minX = Math.max(currentChunk.getMinBlockX(), Math.min(previous.getX(), middle.getX()) - 10);
        int maxX = Math.min(currentChunk.getMaxBlockX(), Math.max(previous.getX(), middle.getX()) + 10);
        int minZ = Math.max(currentChunk.getMinBlockZ(), Math.min(previous.getZ(), middle.getZ()) - 10);
        int maxZ = Math.min(currentChunk.getMaxBlockZ(), Math.max(previous.getZ(), middle.getZ()) + 10);

        List<TemplatePlacement> placements = new ArrayList<>();
        boolean[] blocked = {false};
        TemplateBlockConsumer collect = (pos, state) -> {
            if (!ownsTarget(currentChunk, pos)) {
                return false;
            }
            // Per-voxel wall veto: the bridge stops at the wall instead of overwriting it.
            if (isWallOwned(pos)) return false;
            if (access.isProtected(pos, road)) {
                blocked[0] = true;
                return false;
            }
            placements.add(new TemplatePlacement(pos, state));
            return true;
        };
        boolean clampToBody = access.isOpenWater(middle);
        ColumnProjection projection = projectColumns(bridge, line, segment, deckY, minX, maxX, minZ, maxZ);
        TemplateVisit visit = visitTemplateBlocks(bridge, projection, deckY, clampToBody, collect);

        // 0.9.033（O-14「陆侧桥口缺甲板」）：模板的**桥台段**在甲板高度上是空的
        // （{@link #visitTemplateBlocks} 里的原文：the abutment layers are empty exactly
        // where a deck would be）。水上桥口靠「把列映射压回桥身」绕开了这一点，陆侧桥口
        // 没有 —— 于是陆侧落桥站永远拿不到 road-column 甲板体素 ⇒ handles(landing)=false
        // ⇒ processSegment 返回 false ⇒ PathFeature 在「本 chunk 不拥有该站」时直接
        // continue，那一站在邻接 chunk 里就完全没有甲板，正好是一个 1 站宽的桥口缺口。
        // 修法：陆侧落桥站第一次拿不到甲板时，改用水上桥口那套桥身映射再走一遍；
        // 若仍拿不到，逐字节退回第一次的结果（行为与 0.9.032 完全一致）。
        if (!blocked[0] && landingStation && !clampToBody && !visit.handles(landingStation)) {
            List<TemplatePlacement> firstAttempt = List.copyOf(placements);
            placements.clear();
            blocked[0] = false;
            TemplateVisit forced = visitTemplateBlocks(
                    bridge, projection, deckY, true, collect);
            if (!blocked[0] && forced.handles(landingStation)) {
                visit = forced;
                long filled = LANDING_MOUTHS_FILLED.incrementAndGet();
                if (filled <= 8 || filled % 32 == 0) {
                    LOGGER.info("【模板桥台无桥面，使用桥身列映射补齐接岸】 " + "RoadWeaver bridge landing mouth had no deck in the template abutment; "
                                    + "used the body column mapping instead deckY={} placedBlocks={} "
                                    + "coveredRoadColumns={} count={}",
                            deckY, forced.placedBlocks(), forced.coveredRoadColumns(), filled);
                }
            } else {
                placements.clear();
                placements.addAll(firstAttempt);
                blocked[0] = false;
            }
        }

        if (blocked[0] || !visit.handles(landingStation)) return false;

        // 0.9.005：模板桥过去一行 clear 都没有 —— 只把模板 voxel 贴上去，桥面及其上方的山体/树
        // 原样留着（真机就是"桥两侧有板、中间是石墙"）。这里在贴之前把桥面列的竖向净空带清掉：
        // 只清 [桥面+1, 桥面+1+净空)，向下不动，所以不会挖成峡谷；清在贴之前，且跳过本段模板
        // 自己要占的格子，避免削掉本段的栏杆/桥面。受保护方块由 roadAppearance.forcedClearProtected
        // 决定是否强清（关掉即完全回到 0.9.004 行为）。
        access.clearHeadroom(segment, deckY, road);

        for (TemplatePlacement placement : placements) {
            if (isWallOwned(placement.pos())) continue;
            access.setBlock(placement.pos(), placement.state());
            BlockState updatedState = access.updateFromNeighbourShapes(
                    access.getBlockState(placement.pos()), placement.pos());
            access.setBlock(placement.pos(), updatedState);
        }

        placeTemplateCenterLight(access, road, line, deckY, placements);
        placeTemplatePiers(access, currentChunk, road, line, deckY);

        // A bridge block elsewhere in this chunk does not prove that the
        // current station reaches the bridge. Let the normal bridge fallback
        // pave an uncovered bank transition instead of leaving a one-block gap.
        return true;
    }

    /** 检查列顶是否为开放流体，避免把水上桥口当作普通陆基。 */
    private static boolean isWallOwned(BlockPos pos) {
        return pos != null && net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE,
                pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean isOpenWaterColumn(WorldGenLevel world, BlockPos pos) {
        if (world == null || pos == null) return false;
        int top = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                pos.getX(), pos.getZ()) - 1;
        if (top <= world.getMinBuildHeight()) return false;
        return !world.getBlockState(new BlockPos(pos.getX(), top, pos.getZ())).getFluidState().isEmpty();
    }

    /** 未显式限制时仍限制支撑向下探测范围，避免无限扫描。 */
    private static final int DEFAULT_PIER_DESCENT_CAP = 48;

    /** 按桥墩间隔向首个实地补支撑，遵守高度、当前区块及保护限制，避免模板自带短墩停在水面。
 *
     * Carries a template bridge down to solid ground under every pier interval.
     *
     * <p>The bundled templates are eight blocks tall: their piers are three logs that end on the template's
     * bottom row, which maps to roughly the water surface. Nothing below that row is ever placed, so a
     * template bridge over anything deeper than a ford has no support at all - the logs hang in the water
     * and stop exactly at the surface, which is what the measured renderings showed. This walks the span and
     * fills a support column from just under the deck down to the first solid block, honouring the configured
     * pier interval, the pier height cap, chunk ownership and protected blocks.
     */
    private static void placeTemplatePiers(PlacementAccess access, ChunkPos currentChunk, RoadData road,
                                           Line line, int deckY) {
        int interval = Math.max(2, ConfigService.get().bridge().pierInterval());
        int cap = ConfigService.get().bridge().pierMaxHeight();
        if (cap <= 0) cap = DEFAULT_PIER_DESCENT_CAP;
        BlockState support = BridgeMaterialPalette.forRange(road, 0, 0).support();
        double length = Math.max(1.0D, line.getTotalLength());
        for (double at = interval * 0.5D; at < length; at += interval) {
            Vec3 point = line.pointAt(Math.min(1.0D, at / length));
            int x = (int) Math.floor(point.x);
            int z = (int) Math.floor(point.z);
            boolean placed = false;
            for (int depth = 1; depth <= cap; depth++) {
                int y = deckY - 1 - depth;
                if (y <= access.minBuildHeight()) break;
                BlockPos pos = new BlockPos(x, y, z);
                if (!ownsTarget(currentChunk, pos)) break;
                BlockState existing = access.getBlockState(pos);
                if (existing.blocksMotion()) break;
                if (access.isProtected(pos, road)) break;
                if (isWallOwned(pos)) break;
                access.setBlock(pos, support);
                placed = true;
            }
            if (placed) {
                PIERS_PLACED.incrementAndGet();
            }
        }
    }

    /** 海晶灯按桥自身跨度里程布置，使用本区块实际放出的模板列，不跨区块补灯。
 *
     * Replaces the template deck's center block with a sea lantern every
     * {@code DEFAULT_BRIDGE_LIGHT_INTERVAL_BLOCKS} blocks of bridge span:
     * one at 0, one at 16, one at 32, and so on up to the deck length. The
     * offset is measured along the bridge's own geometry line (0..span
     * length), not along the whole route, so short bridges get lanterns
     * across their entire length instead of only at the two landings.
     * Columns owned by another chunk are not present in {@code placements},
     * so every chunk place only its own share of the lanterns.
     */
    private static void placeTemplateCenterLight(
            PlacementAccess access,
            RoadData road,
            Line line,
            int deckY,
            List<TemplatePlacement> placements) {
        // 读取桥梁专用间隔，0只关闭额外桥灯，不删除模板本身保存的装饰。
        int interval = ConfigService.get().bridge().lightIntervalBlocks();
        if (interval <= 0 || placements == null || placements.isEmpty()) return;
        double length = line.getTotalLength();
        if (!(length > 0.0D)) return;
        for (double spanDistance = 0.0D; spanDistance <= length; spanDistance += interval) {
            BlockPos lightPos = deckCenterColumn(line, spanDistance / length, deckY, placements);
            if (lightPos == null) continue;
            if (access.isProtected(lightPos, road)) continue;
            if (isWallOwned(lightPos)) continue;
            BlockState existing = access.getBlockState(lightPos);
            if (existing.isAir() || !existing.getFluidState().isEmpty()) continue;
            if (existing.is(Blocks.SEA_LANTERN)) continue;
            // O-14（0.9.027）：「海晶灯抢栏杆」。
            // 这一格是**桥面中心列**上的模板体素，可能是桥板（整格实心），也可能是栏杆/栅栏/墙。
            // 原来只判「非空气且非流体」就把整块换成海晶灯 —— 撞上栏杆时等于把栏杆挖掉一段。
            // 栏杆类方块的碰撞体不是整格，用碰撞体是否填满整格来区分：只允许替换桥板。
            if (!access.isFullBlock(existing, lightPos)) continue;
            // 斜向桥:海晶灯沿桥中心线每 interval 格放一个,但斜向投影到世界
            // XZ 时相邻灯会紧挨(斜对角相邻)。放灯前查周围 3x3 是否已有海晶
            // 灯,有则跳过,避免斜向桥桥面出现紧挨/重叠的灯。海晶灯数量极少,
            // 每灯多查 8 格 getBlockState,O(1) 成本可忽略,且仅在 worldgen
            // 后台线程执行。
            if (hasNearbySeaLantern(access, lightPos)) continue;
            access.setBlock(lightPos, Blocks.SEA_LANTERN.defaultBlockState());
        }
    }

    /**
     * 斜向桥海晶灯去重:检查中心格周围 3x3(上下左右+斜对角)是否已有海晶
     * 灯。桥面海晶灯只会是本模组放置(替换 deck 中心方块),周围若已有海晶灯
     * 即视为本模组已放,跳过当前灯以避免斜向紧挨。
     */
    private static boolean hasNearbySeaLantern(PlacementAccess access, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (access.getBlockState(center.offset(dx, 0, dz)).is(Blocks.SEA_LANTERN)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 读取本次实际写入的中心甲板体素，避免忽略模板高度偏移而猜错灯Y。
 *
     * The template voxel at the deck center for one span offset, or null when
     * this invocation did not write that column. Uses the actual template
     * voxel written for the current chunk, which avoids guessing the deck Y
     * when a template has a height offset.
     */
    private static BlockPos deckCenterColumn(Line line,
                                             double t,
                                             int deckY,
                                             List<TemplatePlacement> placements) {
        Vec3 center = line.pointAt(Math.max(0.0D, Math.min(1.0D, t)));
        int columnX = (int) Math.floor(center.x);
        int columnZ = (int) Math.floor(center.z);
        BlockPos exact = null;
        double exactScore = Double.POSITIVE_INFINITY;
        BlockPos near = null;
        double nearScore = Double.POSITIVE_INFINITY;
        for (TemplatePlacement placement : placements) {
            BlockPos pos = placement.pos();
            int dx = pos.getX() - columnX;
            int dz = pos.getZ() - columnZ;
            if (dx == 0 && dz == 0) {
                double score = Math.abs(pos.getY() - deckY);
                if (score < exactScore) {
                    exactScore = score;
                    exact = pos;
                }
                continue;
            }
            double lateral = dx * dx + dz * dz;
            if (lateral > 2.25D) continue;
            double score = lateral + Math.abs(pos.getY() - deckY) * 0.25D;
            if (score < nearScore) {
                nearScore = score;
                near = pos;
            }
        }
        return exact != null ? exact : near;
    }

    /** 存档前校验使用模板精确非空气体素，避免只验中心线。 */
    public static List<BlockPos> plannedBlocks(
            ServerLevel level,
            Line line,
            RoadSegmentPlacement segment,
            BlockPos middle,
            BlockPos previous,
            int deckY,
            boolean landingStation) {
        return plannedBridge(level, line, segment, middle, previous, deckY, landingStation).blocks();
    }

    /** 同时返回模板几何与程序化主体抑制信息，使校验覆盖真正会写出的体积。 */
    public static PlannedBridge plannedBridge(
            ServerLevel level,
            Line line,
            RoadSegmentPlacement segment,
            BlockPos middle,
            BlockPos previous,
            int deckY,
            boolean landingStation) {
        return plannedBridgeWithAccess(level == null ? null : new WorldPlacementAccess(level),
                line, segment, middle, previous, deckY, landingStation);
    }

    static PlannedBridge plannedBridgeWithAccess(
            PlanningAccess access,
            Line line,
            RoadSegmentPlacement segment,
            BlockPos middle,
            BlockPos previous,
            int deckY,
            boolean landingStation) {
        if (access == null || line == null || segment == null || middle == null || previous == null) {
            return PlannedBridge.none();
        }
        BridgeTemplate bridge = access.template(stableBridgeSeed(line));
        if (bridge == null) return PlannedBridge.none();
        int minX = Math.min(previous.getX(), middle.getX()) - 10;
        int maxX = Math.max(previous.getX(), middle.getX()) + 10;
        int minZ = Math.min(previous.getZ(), middle.getZ()) - 10;
        int maxZ = Math.max(previous.getZ(), middle.getZ()) + 10;
        List<BlockPos> blocks = new ArrayList<>();
        TemplateBlockConsumer collectAll = (pos, state) -> {
            blocks.add(pos);
            return true;
        };
        boolean clampToBody = access.isOpenWater(middle);
        ColumnProjection projection = projectColumns(bridge, line, segment, deckY, minX, maxX, minZ, maxZ);
        TemplateVisit visit = visitTemplateBlocks(bridge, projection, deckY, clampToBody, collectAll);
        // 0.9.033：与 processSegment 保持**逐条一致**的重试，否则存档期校验算出的
        // centerChunkHandles 会和真正生成的体素对不上（退回程序化桥面会双铺）。
        if (landingStation && !clampToBody && !visit.handles(landingStation)) {
            List<BlockPos> firstAttempt = List.copyOf(blocks);
            blocks.clear();
            TemplateVisit forced = visitTemplateBlocks(
                    bridge, projection, deckY, true, collectAll);
            if (forced.handles(landingStation)) {
                visit = forced;
            } else {
                blocks.clear();
                blocks.addAll(firstAttempt);
            }
        }
        if (!visit.handles(landingStation)) return PlannedBridge.none();

        ChunkPos centerChunk = new ChunkPos(middle);
        Set<Long> roadColumns = projection.roadColumns();
        int centerBlocks = 0;
        int centerRoadColumns = 0;
        for (BlockPos block : blocks) {
            if (!ownsTarget(centerChunk, block)) continue;
            centerBlocks++;
            if (block.getY() >= deckY - 4 && block.getY() <= deckY + 3
                    && roadColumns.contains(columnKey(block.getX(), block.getZ()))) {
                centerRoadColumns++;
            }
        }
        boolean centerChunkHandles = centerBlocks > 0 && (!landingStation || centerRoadColumns > 0);
        return new PlannedBridge(List.copyOf(blocks), centerChunkHandles);
    }

    private static TemplateVisit visitTemplateBlocks(BridgeTemplate bridge, ColumnProjection projection,
            int deckY, boolean clampColumnToBody, TemplateBlockConsumer consumer) {
        int placedBlocks = 0;
        int coveredRoadColumns = 0;
        // The bundled templates put their deck body in the middle and their abutments at the ends; the
        // abutment layers are empty exactly where a deck would be, so the first and last few columns of every
        // template bridge have no surface at all. On land the abutment sits in the bank and nobody notices.
        // Over open water it is a hole straight into the river, so there the body is used all the way to the
        // mouth - the piers added by placeTemplatePiers are what carries it down.
        //
        // 0.9.033：同一件事在**陆侧**桥口也是缺口，只是被「邻接 chunk 不拥有该站就 continue」
        // 藏住了（见 processSegment 里的说明）。所以 clampColumnToBody 现在有两个来源：
        // 水上桥口，以及陆侧桥口第一次拿不到甲板时的重试。
        double bodyLow = bridge.getStartLength();
        double bodyHigh = Math.max(bodyLow, bridge.getTotalLength() - bridge.getEndLength() - 1.0E-4D);
        for (ProjectedColumn column : projection.columns()) {
            double localX = clampColumnToBody ? Math.max(bodyLow, Math.min(bodyHigh, column.templateX()))
                    : column.templateX();
            for (int y = deckY + 20; y >= deckY - 60; y--) {
                double dy = y - column.centerY();
                // 沿用点积原有运算顺序，模板恰好贴体素边界时也不能换格。
                double localY = column.normalX() + dy * column.normalY() + column.normalZ();
                double localZ = column.binormalX() + dy * column.binormalY() + column.binormalZ();
                BlockState state = bridge.getBlock(localX, localY, localZ);
                if (state == null || state.is(Blocks.AIR)) continue;
                if (!consumer.accept(new BlockPos(column.x(), y, column.z()), state)) continue;
                placedBlocks++;
                if (column.onRoad() && y >= deckY - 4 && y <= deckY + 3) coveredRoadColumns++;
            }
        }
        return new TemplateVisit(placedBlocks, coveredRoadColumns);
    }

    /** 只投影静态坐标；碰撞、实际水位、支撑和写方块仍由各自的入口负责。 */
    private static ColumnProjection projectColumns(BridgeTemplate bridge, Line line, RoadSegmentPlacement segment,
            int deckY, int minX, int maxX, int minZ, int maxZ) {
        double length = line.getTotalLength();
        Set<Long> roadColumns = footprintColumns(segment);
        List<ProjectedColumn> columns = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vec3 testPos = new Vec3(x, deckY, z);
                Line.Frame frame = line.getFrame(testPos);
                Vec3 offset = testPos.subtract(frame.closestPoint);
                if (Math.abs(offset.dot(frame.tangent0)) > 2 || !bridge.isInVoxelGrid(0, 0, offset.dot(frame.binormal0))) {
                    continue;
                }
                double along = Math.min(Math.max(0, frame.globalT), 1) * length;
                double localX = mapTemplateX(along, length, bridge.getStartLength(),
                        bridge.getEndLength(), bridge.getDeckLength(), bridge.getTotalLength());
                // 陆侧桥头改用桥身时只改变 localX 的夹取，曲线帧与列坐标不应重新计算。
                columns.add(new ProjectedColumn(x, z, localX, frame.closestPoint.y,
                        offset.x * frame.normal0.x, frame.normal0.y, offset.z * frame.normal0.z,
                        offset.x * frame.binormal0.x, frame.binormal0.y, offset.z * frame.binormal0.z,
                        roadColumns.contains(columnKey(x, z))));
            }
        }
        return new ColumnProjection(columns, roadColumns);
    }

    private record ColumnProjection(List<ProjectedColumn> columns, Set<Long> roadColumns) {}

    private record ProjectedColumn(int x, int z, double templateX, double centerY,
                                   double normalX, double normalY, double normalZ,
                                   double binormalX, double binormalY, double binormalZ, boolean onRoad) {}

    @FunctionalInterface
    private interface TemplateBlockConsumer {
        boolean accept(BlockPos pos, BlockState state);
    }

    private record TemplateVisit(int placedBlocks, int coveredRoadColumns) {
        private boolean handles(boolean landingStation) {
            return placedBlocks > 0 && (!landingStation || coveredRoadColumns > 0);
        }
    }

    private record TemplatePlacement(BlockPos pos, BlockState state) {
    }

    public record PlannedBridge(List<BlockPos> blocks, boolean centerChunkHandles) {
        public PlannedBridge {
            blocks = blocks == null || blocks.isEmpty() ? List.of() : List.copyOf(blocks);
        }

        public static PlannedBridge none() {
            return new PlannedBridge(List.of(), false);
        }
    }

    static double mapTemplateX(double localX,
                               double bridgeLength,
                               int startLength,
                               int endLength,
                               int deckLength,
                               int totalLength) {
        double mapped = localX;
        double repeatEnd = bridgeLength - endLength;
        if (mapped >= startLength && mapped < repeatEnd && deckLength > 0) {
            double offset = (mapped - startLength) % deckLength;
            if (offset < 0.0D) offset += deckLength;
            mapped = startLength + offset;
        } else if (mapped >= repeatEnd) {
            mapped = totalLength - (bridgeLength - mapped);
        }
        // The endpoint at t=1 previously mapped to sizeX exactly, which is one
        // column outside the template and made the bridge stop before the bank.
        double maximum = Math.max(0.0D, totalLength - 1.0E-4D);
        return Math.max(0.0D, Math.min(maximum, mapped));
    }

    private static Set<Long> footprintColumns(RoadSegmentPlacement segment) {
        Set<Long> columns = new HashSet<>();
        if (segment == null) return columns;
        if (segment.middlePos() != null) {
            columns.add(columnKey(segment.middlePos().getX(), segment.middlePos().getZ()));
        }
        if (segment.positions() != null) {
            for (BlockPos position : segment.positions()) {
                if (position != null) columns.add(columnKey(position.getX(), position.getZ()));
            }
        }
        return columns;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static boolean ownsTarget(ChunkPos chunk, BlockPos pos) {
        return pos.getX() >= chunk.getMinBlockX() && pos.getX() <= chunk.getMaxBlockX()
                && pos.getZ() >= chunk.getMinBlockZ() && pos.getZ() <= chunk.getMaxBlockZ();
    }

    /** 由桥端点生成稳定模板种子，避免各区块观察到的局部长度不同而选出不同桥体。
 *
     * Derives a stable template seed from the bridge's endpoints, independent of the bridge length
     * that a chunk happens to observe at generation time. The old seed was {@code (int) bridgeLength},
     * and because the runtime bridge range could grow differently per chunk, the same bridge could
     * pick different templates in different chunks ("two templates" across a chunk border). Endpoints
     * are fixed for a given bridge, so the seed is deterministic regardless of which chunk generates
     * first.
     */
    private static int stableBridgeSeed(Line line) {
        if (line == null) return 0;
        Vec3 start = line.pointAt(0.0D);
        Vec3 end = line.pointAt(1.0D);
        long minX = Math.round(Math.min(start.x, end.x));
        long minZ = Math.round(Math.min(start.z, end.z));
        long maxX = Math.round(Math.max(start.x, end.x));
        long maxZ = Math.round(Math.max(start.z, end.z));
        long h = minX * 3129871L ^ minZ * 116129781L ^ maxX * 42319L ^ maxZ * 97L;
        return (int) (h ^ (h >>> 32));
    }
}
