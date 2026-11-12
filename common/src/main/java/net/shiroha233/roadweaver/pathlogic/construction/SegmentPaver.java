/* 文件职责：铺设道路段的基础路面。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.PresetService;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.decoration.system.SurfacePlacementUtil;
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadHeightInterpolator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder;
import net.shiroha233.roadweaver.pathlogic.tunnel.TunnelClearLedger;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.core.Road;

/**
 * 路面铺设器
 */
public final class SegmentPaver {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final AtomicLong UNSUPPORTED_STATIONS = new AtomicLong();
    /** Road lip supports placed this session; a diagnostic, never a gate. */
    private static final AtomicLong SHOULDER_SUPPORTS = new AtomicLong();
    /** 0.9.033：受保护路基上照常铺了路面的列数（O-14「单列保护漏单格洞」的取证计数）。 */
    private static final AtomicLong PAVED_OVER_PROTECTED_ROADBED = new AtomicLong();
    private SegmentPaver() {}

    /** {@return how many road-surface cells this process filled over a protected roadbed} */
    public static long pavedOverProtectedRoadbed() {
        return PAVED_OVER_PROTECTED_ROADBED.get();
    }

    /** 一列路的三种处置；抽成纯枚举是为了让优先级能被单测钉住。 */
    public enum ColumnAction {
        /** 路面格本身受保护：必须跳过，跳过的是受保护方块自己那一格。 */
        SKIP,
        /** 只有路基（路面下一格）受保护：照常铺路面，但**绝不能**重写 pos.below()。 */
        PAVE_SURFACE_ONLY,
        /** 都不受保护：走原来的 placeOnSurface 全流程。 */
        PAVE_NORMAL
    }

    /**
     * 0.9.033（O-14「单列保护漏单格洞」）：原来 {@code protectedColumn = 路面格受保护 || 路基受保护}
     * 两种都 {@code continue}，于是路面下只要有一个箱子/告示牌等方块实体，这一列路面就被整格跳过、
     * 左右邻列照常铺 —— 驾驶面上出现 1×1 的坑。
     *
     * <p>优先级必须是「路面格 > 路基」：两者都受保护时跳过（不能动受保护方块本身）；
     * 只有路基受保护时铺路面，但那条路径不许走 {@code placeOnSurface}，因为
     * {@code RoadBlockPlacer.placeRoadBlock} 会**无条件** {@code setBlock(pos.below(), chosen)}，
     * 正好会把受保护的那一格覆盖掉。</p>
     */
    static ColumnAction classifyProtectedColumn(boolean surfaceProtected, boolean roadbedProtected) {
        if (surfaceProtected) return ColumnAction.SKIP;
        if (roadbedProtected) return ColumnAction.PAVE_SURFACE_ONLY;
        return ColumnAction.PAVE_NORMAL;
    }

    public static SegmentGrade planGrade(WorldGenLevel world,
            RoadSegmentPlacement seg,
            int segmentIndex,
            List<BlockPos> centers,
            int[] targetY,
            boolean preserveTunnelGrade) {
        return planGrade(world, seg, segmentIndex, centers, targetY, preserveTunnelGrade, false);
    }

    public static SegmentGrade planGrade(WorldGenLevel world,
            RoadSegmentPlacement seg,
            int segmentIndex,
            List<BlockPos> centers,
            int[] targetY,
            boolean preserveTunnelGrade,
            boolean preserveCrossingGrade) {
        List<BlockPos> positions = seg.positions();
        if (positions.isEmpty()) return SegmentGrade.empty();
        int[] heights = RoadHeightInterpolator.batchInterpolate(positions, segmentIndex, centers, targetY);
        if (!preserveCrossingGrade) {
            RoadConstructionSafety.prepareCulvertDeck(world, positions, heights, segmentIndex, centers);
        }
        int[] terrain = RoadConstructionSafety.sampleTerrain(world, positions);
        boolean preservePlannedGrade = preserveTunnelGrade || preserveCrossingGrade;
        // O-08（0.9.027）：横断面压平只看**隧道**豁免，不再被桥/路口的 ramp 带豁免。
        //
        // 原来这里是 `if (!preservePlannedGrade)` —— 而 preserveCrossingGrade 的覆盖范围是
        // 「跨度本体 + 桥头 ±bridgeRampSegments(默认 4) 站」，于是桥头引道整段不压平，
        // 每一列各走各的高度 ⇒ 真机上就是「桥头逐列阶梯」。
        //
        // 压平不会破坏纵向剖面：resolveRoadCrossSection 取的是**最靠近站点中心线的那一列**
        // 的高度，中心列本来就是它自己的插值高度 ⇒ 中心线（也就是与桥面/另一条路对接的那条线）
        // 逐格不变，只是同一站的两侧列对齐到它。所以桥面平顺、接缝不动。
        if (!preserveTunnelGrade) {
            // Flatten to the height of the column nearest the station's centre line. Taking the median of
            // the whole cross-section made the plate wander by a block between neighbouring stations
            // (0,1,0,1 on a slope), because the width columns project onto slightly different points of the
            // centre line - and on a diagonal that offset is the largest, which is the visible "diagonal
            // stairs" difference.
            RoadConstructionSafety.resolveRoadCrossSection(heights, terrain,
                    RoadConstructionSafety.nearestColumnIndex(positions, seg.middlePos()));
        }
        int surfaceY = RoadConstructionSafety.representativeHeight(heights);
        int beforeY = neighborSurfaceY(world, centers, targetY, segmentIndex - 1, preservePlannedGrade, surfaceY);
        int afterY = neighborSurfaceY(world, centers, targetY, segmentIndex + 1, preservePlannedGrade, surfaceY);
        // A bottom slab belongs to a station that sits strictly between two higher neighbours: that is what
        // turns a one-block dip into a visible half step. The comparison used to accept a neighbour at the
        // same height, so every station of a flat stretch counted as a dip and the valley floor came out as
        // two consecutive stations of half-block pit.
        boolean slabTransition = beforeY > surfaceY && afterY > surfaceY;
        return new SegmentGrade(heights, terrain, preserveTunnelGrade, preserveCrossingGrade, slabTransition);
    }

    public static boolean paveSegment(WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            int segmentIndex,
            List<BlockPos> centers,
            int[] targetY,
            SegmentGrade grade,
            int roadType,
            List<BlockState> materials,
            List<BlockState> slabMaterials,
            RandomSource random,
            ModConfig cfg) {
        return paveSegment(world, road, seg, segmentIndex, centers, targetY, grade, roadType,
                materials, slabMaterials, random, cfg, null);
    }

    /**
     * T-24: 带施工拒绝收集器的变体。refusalOut(可为 null)在站点被
     * fill-too-deep / protected-* 终态性原因拒绝时记录 segmentIndex -> reason,
     * 供 PathFeature 把连续失败站落为施工期 PROTECTED_GAP 并原位替换道路,
     * 使该 chunk 按完成处理而不再进入 1-5 秒重试循环。
     */
    public static boolean paveSegment(WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            int segmentIndex,
            List<BlockPos> centers,
            int[] targetY,
            SegmentGrade grade,
            int roadType,
            List<BlockState> materials,
            List<BlockState> slabMaterials,
            RandomSource random,
            ModConfig cfg,
            Map<Integer, String> refusalOut) {
        return paveSegment(world, road, seg, segmentIndex, centers, targetY, grade, roadType,
                materials, slabMaterials, random, cfg, refusalOut, false);
    }

    /**
     * 0.9.065：{@code openCutStation = true} 表示这一站被 {@link RoadTunnelBuilder} 判成
     * <b>洞口明挖段</b>（span 两端 Mouth / 端点洞口 / 头顶没岩石的那几站）。处置分两档：
     * <ul>
     *   <li>洞身站（隧道站且不是明挖段）：{@link AboveColumnClearer#clearTunnelBoreHeadroom}
     *       只清洞身净空，绝不越过洞顶向上清；</li>
     *   <li>洞口明挖站 / 普通路段：仍走 7 参 {@code clearAboveColumn}（含凸起削平），
     *       行为与 0.9.064 逐格一致。</li>
     * </ul>
     */
    public static boolean paveSegment(WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            int segmentIndex,
            List<BlockPos> centers,
            int[] targetY,
            SegmentGrade grade,
            int roadType,
            List<BlockState> materials,
            List<BlockState> slabMaterials,
            RandomSource random,
            ModConfig cfg,
            Map<Integer, String> refusalOut,
            boolean openCutStation) {
        List<BlockPos> positions = seg.positions();
        if (positions.isEmpty()) return true;
        if (grade == null || grade.heights().length != positions.size()) return false;

        int[] heights = grade.heights();
        boolean preservePlannedGrade = grade.preserveTunnelGrade() || grade.preserveCrossingGrade();
        // 0.9.065：洞身段禁止向上清空（岩体就是顶）；洞口明挖段与普通路段不受影响。
        boolean tunnelBody = tunnelBodyStation(grade.preserveTunnelGrade(), openCutStation);
        int[] tunnelSpan = grade.preserveTunnelGrade()
                ? RoadTunnelBuilder.tunnelScope(road, centers, segmentIndex) : null;
        TunnelClearLedger.Scope tunnelScope = tunnelSpan == null
                ? null : new TunnelClearLedger.Scope(tunnelSpan[0], tunnelSpan[1]);
        if (tunnelBody) TunnelClearLedger.noteBodyStation(tunnelScope);
        if (!preservePlannedGrade) {
            int clearHeight = cfg.roadClearHeight();
            for (int i = 0; i < positions.size(); i++) {
                BlockPos widthBlock = positions.get(i);
                BlockPos surface = new BlockPos(widthBlock.getX(), heights[i], widthBlock.getZ());
                if (StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, surface, road)
                        || StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, surface.below(), road)) {
                    continue;
                }
                AboveColumnClearer.ClearanceCheck clearance = AboveColumnClearer.checkAboveColumn(
                        world, surface, road, clearHeight, true);
                if (!clearance.ready()) {
                    logSkippedStation(road, segmentIndex, clearance.reason(), clearance.position(), 0);
                    if (refusalOut != null) refusalOut.put(segmentIndex, clearance.reason());
                    return false;
                }
            }
            RoadConstructionSafety.GradePreparation preparation =
                    RoadConstructionSafety.prepareRoadGrade(
                            world, road, seg.middlePos(), positions, heights, grade.terrain());
            if (!preparation.ready()) {
                logSkippedStation(road, segmentIndex, preparation.reason(),
                        preparation.position(), preparation.depth());
                if (refusalOut != null) refusalOut.put(segmentIndex, preparation.reason());
                // Planning normally lowers or smooths these before the road
                // reaches worldgen. If terrain changed after planning and the
                // remaining fill is still too deep, report a real construction
                // failure so the route cannot enter the completion ledger
                // while this station is still unbuilt.
                if ("fill-too-deep".equals(preparation.reason())
                        || "fill-too-deep-center".equals(preparation.reason())) {
                    LOGGER.warn("RoadWeaver rejected terrain construction gap road={} segment={} pos={} depth={}",
                            road != null && road.hasOwnerPair()
                                    ? road.ownerA2dKey() + "->" + road.ownerB2dKey() : "unknown",
                            segmentIndex, preparation.position(), preparation.depth());
                    return false;
                }
                return false;
            }
        }
        Map<ResourceLocation, NaturalMaterials> naturalMaterials = roadType == 1 ? new HashMap<>() : Map.of();

        for (int i = 0; i < positions.size(); i++) {
            BlockPos widthBlock = positions.get(i);
            int y = heights[i];
            BlockPos pos = new BlockPos(widthBlock.getX(), y, widthBlock.getZ());

            // A route-level collision is handled by planning.  This is only a
            // last-resort block guard: do not delete a block entity, a huge
            // tree, or sanctuary material while a route is being rebuilt.
            //
            // 0.9.033（O-14）：原来把「路面格受保护」与「路面**下一格**受保护」并成一个
            // 条件、两种都 continue —— 路面下只要有一个方块实体（isProtectedState 覆盖
            // hasBlockEntity），这一列路面就被整格跳过，而左右邻列照常铺，驾驶面上留下
            // 1×1 的坑。判定与处置见 classifyProtectedColumn。
            ColumnAction columnAction = classifyProtectedColumn(
                    RoadConstructionSafety.isProtectedPreparedBlock(world, pos, road),
                    RoadConstructionSafety.isProtectedPreparedBlock(world, pos.below(), road));
            if (columnAction == ColumnAction.SKIP) {
                LOGGER.debug("RoadWeaver preserved construction column road={} segment={} pos={}",
                        road != null && road.hasOwnerPair()
                                ? road.ownerA2dKey() + "->" + road.ownerB2dKey() : "unknown",
                        segmentIndex, pos);
                continue;
            }

            List<BlockState> baseMats;
            List<BlockState> slabs;
            if (roadType == 1) {
                ResourceLocation biomeId = world.getBiome(pos).unwrapKey().map(key -> key.location()).orElse(null);
                NaturalMaterials selected = naturalMaterials.computeIfAbsent(
                        biomeId, ignored -> resolveNaturalMaterials(world, pos, biomeId));
                baseMats = selected.base();
                slabs = selected.slabs();
            } else if (materials != null && !materials.isEmpty()) {
                baseMats = materials;
                slabs = slabMaterials;
            } else {
                baseMats = List.of();
                slabs = List.of();
            }

            if (!baseMats.isEmpty()) {
                int clearHeight = grade.preserveTunnelGrade() ? cfg.tunnelClearHeight() : cfg.roadClearHeight();
                if (columnAction == ColumnAction.PAVE_SURFACE_ONLY) {
                    // 0.9.033：受保护的是 pos.below()，所以这一列**绝不能**走 placeOnSurface ——
                    // RoadBlockPlacer.placeRoadBlock 会无条件 setBlock(pos.below(), chosen)，
                    // 正好把受保护方块的状态改掉（那正是台账警告的「覆盖受保护方块」）。
                    // 这里只做「清净空 + 铺路面那一格」，pos.below() 一个字节都不碰。
                    if (tunnelBody) {
                        // 0.9.065：洞身段只清洞身净空 —— 上方岩体就是顶板，不能往上削。
                        AboveColumnClearer.clearTunnelBoreHeadroom(world, pos, cfg, road, clearHeight);
                    } else {
                        AboveColumnClearer.clearAboveColumn(world, pos, cfg, road, clearHeight);
                    }
                    noteClear(tunnelScope, tunnelBody);
                    world.setBlock(pos, baseMats.get(random.nextInt(baseMats.size())), 3);
                    long paved = PAVED_OVER_PROTECTED_ROADBED.incrementAndGet();
                    if (paved <= 8 || paved % 64 == 0) {
                        LOGGER.info("RoadWeaver filled road surface over a protected roadbed road={} "
                                        + "segment={} pos={} count={}",
                                road != null && road.hasOwnerPair()
                                        ? road.ownerA2dKey() + "->" + road.ownerB2dKey() : "unknown",
                                segmentIndex, pos, paved);
                    }
                } else {
                    SurfacePlacementUtil.placeOnSurface(world, pos, baseMats, roadType, random, cfg, road, clearHeight,
                            !grade.preserveTunnelGrade(), tunnelBody);
                    noteClear(tunnelScope, tunnelBody);
                }
                // A road block with exactly one cell of air under it is a lip over nothing: the edge of the
                // road hangs, and from the side it reads as a floating slab. Support it with the road's own
                // material. Only a one-cell pocket over solid ground qualifies, so this cannot cut a slope,
                // cannot fill a river and cannot build the parallel strips that made the old shoulder
                // grading a dead end.
                BlockPos under = pos.below();
                if (world.getBlockState(under).isAir()
                        && world.getBlockState(under.below()).blocksMotion()
                        && !net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE,
                                under.getX(), under.getY(), under.getZ())) {
                    world.setBlock(under, baseMats.get(random.nextInt(baseMats.size())), 3);
                    long supports = SHOULDER_SUPPORTS.incrementAndGet();
                    if (supports <= 16 || supports % 256 == 0) {
                        LOGGER.debug("RoadWeaver supported a road lip segment={} pos={} count={}",
                                segmentIndex, under, supports);
                    }
                }
            }

            if (slabs != null && !slabs.isEmpty()) {
                if (grade.slabTransition()
                        && !net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE,
                                pos.getX(), pos.getY(), pos.getZ())) {
                    BlockState slabState = slabs.get(random.nextInt(slabs.size()));
                    if (slabState.getBlock() instanceof SlabBlock) {
                        slabState = slabState.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                    }
                    world.setBlock(pos, slabState, 3);
                }
            }
        }
        // Road shoulders are intentionally disabled. Keep the planned road
        // grade and footprint only; extending side columns caused unwanted
        // parallel fill/cut strips on steep terrain.
        // 0.9.065：一段隧道一行 —— 在它的最后一站铺完之后打 [TUNNEL-CLEAR]。
        if (tunnelScope != null && segmentIndex >= tunnelScope.spanLast()) {
            TunnelClearLedger.report(tunnelScope);
        }
        return true;
    }

    /**
     * 0.9.065：洞身段判定 —— 隧道站（{@code preserveTunnelGrade}）且不是洞口明挖段。
     * 抽成纯函数，让「洞身 / 明挖 / 普通」三档能被单测钉住，而不是埋在施工流程里。
     */
    public static boolean tunnelBodyStation(boolean preserveTunnelGrade, boolean openCutStation) {
        return preserveTunnelGrade && !openCutStation;
    }

    /** 0.9.065：[TUNNEL-CLEAR] 台账记一列路面（洞身列 = 跳过向上清空，明挖列 = 照常清空）。 */
    private static void noteClear(TunnelClearLedger.Scope scope, boolean tunnelBody) {
        if (scope == null) return;
        if (tunnelBody) {
            TunnelClearLedger.noteSkippedClear(scope, 1);
        } else {
            TunnelClearLedger.notePortalClear(scope, 1);
        }
    }

    private static void logSkippedStation(RoadData road, int segmentIndex,
            String reason, BlockPos position, int depth) {
        long count = UNSUPPORTED_STATIONS.incrementAndGet();
        if (count <= 16 || count % 128 == 0) {
            LOGGER.warn("RoadWeaver skipped unsafe ordinary road station road={} segment={} reason={} pos={} depth={} count={}",
                    road != null && road.hasOwnerPair()
                            ? road.ownerA2dKey() + "->" + road.ownerB2dKey() : "unknown",
                    segmentIndex, reason, position, depth, count);
        }
    }

    private static int neighborSurfaceY(WorldGenLevel world, List<BlockPos> centers, int[] targetY,
            int index, boolean preserveTunnelGrade, int fallback) {
        if (centers == null || targetY == null || index < 0 || index >= centers.size() || index >= targetY.length) {
            return fallback;
        }
        BlockPos center = centers.get(index);
        int planned = targetY[index];
        return preserveTunnelGrade ? planned
                : RoadConstructionSafety.clampTargetHeight(world, center.getX(), center.getZ(), planned);
    }

    private static NaturalMaterials resolveNaturalMaterials(WorldGenLevel world,
                                                             BlockPos pos,
                                                             ResourceLocation biomeId) {
        PresetService.PresetDef preset = PresetService.findNaturalPresetForBiome(biomeId);
        if (preset != null) {
            return new NaturalMaterials(
                    PresetService.toBlockStatesFromIdsAllowEmpty(preset.materials()),
                    PresetService.toBlockStatesFromIdsAllowEmpty(preset.slabMaterials()));
        }
        return new NaturalMaterials(
                net.shiroha233.roadweaver.decoration.material.BiomeRoadMaterialSelector
                        .forBiome(world, pos),
                List.of());
    }

    private record NaturalMaterials(List<BlockState> base, List<BlockState> slabs) {
    }

    public record SegmentGrade(int[] heights,
                               int[] terrain,
                               boolean preserveTunnelGrade,
                               boolean preserveCrossingGrade,
                               boolean slabTransition) {
        private static SegmentGrade empty() {
            return new SegmentGrade(new int[0], new int[0], false, false, false);
        }
    }
}
