/* 文件职责：冻结3e2af6a原站循环，仅替换世界边界，与新执行器完整事件对拍。 */
package net.shiroha233.roadweaver.worldgen.road;
import net.shiroha233.roadweaver.pathlogic.construction.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegment;
import net.shiroha233.roadweaver.decoration.base.Decoration;
import net.shiroha233.roadweaver.decoration.system.DecorationPlanner;
import net.shiroha233.roadweaver.pathlogic.construction.BuildFrame;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegmentPlanner;
import net.shiroha233.roadweaver.pathlogic.construction.SegmentPaver;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkSlice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;


import java.util.function.IntPredicate;

final class FrozenConstructionStationWork {
    private static final Logger LOGGER=LoggerFactory.getLogger("roadweaver");
    static boolean run(ConstructionStationWork.Inputs inputs,ConstructionSliceState state,
            ConstructionStationAccess access,IntPredicate claim,Set<Decoration> decorations,
            RandomSource random,Map<Integer,String> refusals) {
        RoadData data=inputs.data();
        long roadFingerprint=inputs.roadFingerprint();
        ChunkPos currentChunk=inputs.currentChunk();
        RoadChunkSlice slice=inputs.slice();
        ModConfig cfg=inputs.cfg();
        boolean bridgeEnabled=inputs.bridgeEnabled();
        int roadType=inputs.roadType();
        int roadWidth=inputs.roadWidth();
        List<BlockState> materials=inputs.materials();
        List<BlockState> slabMaterials=inputs.slabMaterials();
        List<RoadSegmentPlacement> segments=inputs.segments();
        List<BlockPos> middlePositions=inputs.middlePositions();
        boolean[] isBridge=inputs.isBridge();
        List<int[]> bridgeRanges=inputs.bridgeRanges();
        boolean[] skipSegments=inputs.skipSegments();
        ConstructionSpanMasks.Masks spanMasks=inputs.spanMasks();
        boolean useBuoysInstead=inputs.useBuoysInstead();
        boolean useBuoysWhenSkipped=inputs.useBuoysWhenSkipped();
        boolean[] buoyMarkersForBridge=inputs.buoyMarkersForBridge();
        boolean[] buoyMarkersForSkipped=inputs.buoyMarkersForSkipped();
        BridgeSegment bridgeSegment=inputs.bridgeSegment();
        int deckY=inputs.deckY();
        BridgeSegmentPlanner.Context bridgeCtx=inputs.bridgeCtx();
        BuildFrame.Frames buildFrame=inputs.buildFrame();
        int[] baseYArr=state.profile();
        boolean[] completed={true};
        boolean[] constructionRefused=new boolean[segments.size()];
        for (int localIndex = 0; localIndex < slice.segmentCount(); localIndex++) {
            int i = slice.segmentIndexAt(localIndex);
            // Shared stations are navigation data for this later road only.
            // The owner road is solely responsible for pavement, clearing,
            // bridge decks/piers, tunnels, and decorations at these stations.
            if (spanMasks.networkReuseAt(i) || spanMasks.protectedGapAt(i)) continue;
            BlockPos middle = middlePositions.get(i);
            int segmentIndex = Math.max(0, Math.min(segments.size() - 2, i));
            boolean centerOwned = contains(currentChunk, middle);
            boolean templateBridgeCandidate = bridgeEnabled && isBridge[i];
            if (!centerOwned && !templateBridgeCandidate)
                continue;
            if (!claim.test(i))
                continue;

            BlockPos prev = middlePositions.get(Math.max(0, i - 2));
            BlockPos next = middlePositions.get(Math.min(middlePositions.size() - 1, i + 2));

            BlockPos averaged = null;
            int baseYForThis = baseYArr != null ? baseYArr[i] : middle.getY();
            if (centerOwned) {
                int sea = access.seaLevel();
                int motion = access.height(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, middle.getX(), middle.getZ());
                int surface = access.height(Heightmap.Types.WORLD_SURFACE_WG, middle.getX(), middle.getZ());
                int topYCenter = (motion > sea + 2) ? motion : surface;
                averaged = new BlockPos(middle.getX(), topYCenter, middle.getZ());
                if (baseYArr == null) {
                    baseYForThis = topYCenter;
                }
            }

            RoadSegmentPlacement seg = segments.get(i);
            if (skipSegments != null && i >= 0 && i < skipSegments.length && skipSegments[i]) {
                if (!centerOwned) {
                    continue;
                }
                if (useBuoysWhenSkipped && buoyMarkersForSkipped != null && i < buoyMarkersForSkipped.length
                        && buoyMarkersForSkipped[i]) {
                    access.buoy( middle, access.seaLevel(), random, cfg);
                }
                continue;
            }

            if (useBuoysInstead && isBridge != null && i >= 0 && i < isBridge.length && isBridge[i]) {
                if (!centerOwned) {
                    continue;
                }
                if (buoyMarkersForBridge != null && i < buoyMarkersForBridge.length && buoyMarkersForBridge[i]) {
                    access.buoy( middle, access.seaLevel(), random, cfg);
                }
                continue;
            }

            if (bridgeEnabled && isBridge[i]) {
                var line = bridgeSegment.getLine(i);
                int actualDeckY = baseYForThis;
                boolean placedTemplate = line != null && access.templateBridge( currentChunk, data, line, seg, middle, prev, actualDeckY,
                                bridgeSegment.isLanding(i));
                if (!placedTemplate) {
                    if (!centerOwned) {
                        continue;
                    }
                    boolean placedFallback = access.proceduralBridge( data, seg, middle, prev, next, roadWidth, actualDeckY,
                            deckY, segmentIndex, random, cfg, bridgeRanges, baseYArr, i, bridgeCtx, line);
                    if (!placedFallback) {
                        completed[0] = false;
                        LOGGER.warn("【桥梁站点施工失败】 " + "RoadWeaver bridge segment construction failed road={} segment={} pos={}",
                                data.hasOwnerPair() ? data.ownerA2dKey() + "->" + data.ownerB2dKey() : "unknown",
                                i, middle);
                    }
                }
            } else {
                access.tunnel( data, seg, i, middlePositions, baseYArr,
                        slice.isTunnelJunction(i), tunnel -> {
                    if (tunnel.blocked()) {
                        if (tunnel.protectedGap()) {
                            // One genuinely hard-protected volume (confirmed
                            // structure block, bedrock, block entity, reserved
                            // trunk, sanctuary) stops excavation at exactly
                            // this station. Skip it as an explicit protected
                            // gap: the span keeps building as a tunnel around
                            // it, the chunk completes, and the backfill loop
                            // never retries it.
                            LOGGER.warn("【隧道站点按保护缺口跳过，跨度仍保持隧道类型】 " + "RoadWeaver tunnel station skipped as PROTECTED_GAP road={} segment={} pos={} action=skip_station span_stays_tunnel",
                                    data != null && data.hasOwnerPair()
                                            ? data.ownerA2dKey() + "->" + data.ownerB2dKey() : "unknown",
                                    i, middle);
                            return;
                        }
                        // Task 4: a rejected tunnel must not leave the station
                        // half-cleaned or unpaved. Seat the station back on the
                        // terrain line and build it as an ordinary surface road
                        // (surface-cut fallback); if even that fails the chunk
                        // stays incomplete so the backfill pass retries, and
                        // the ledger never records a half-built tunnel.
                        BlockPos blockedCenter = seg.middlePos();
                        if (blockedCenter != null) {
                            int surfaceY = access.terrain( blockedCenter.getX(), blockedCenter.getZ());
                            int[] fallbackY = baseYArr != null ? baseYArr : new int[middlePositions.size()];
                            fallbackY[i] = surfaceY;
                            LOGGER.warn("【隧道施工被拒，本段按既有规则转为地表切坡】 " + "RoadWeaver tunnel rejected; falling back to surface-cut build road={} segment={} pos={} y={} reason=see_blocked_tunnel_section",
                                    data != null && data.hasOwnerPair()
                                            ? data.ownerA2dKey() + "->" + data.ownerB2dKey() : "unknown",
                                    i, blockedCenter, surfaceY);
                            SegmentPaver.SegmentGrade fallbackGrade = access.grade( seg, i, middlePositions, fallbackY, false,
                                    spanMasks.preserveGradeAt(i));
                            boolean paved = access.pave( data, seg, i, middlePositions,
                                    fallbackY, fallbackGrade, roadType, materials, slabMaterials, random, cfg,
                                    refusals);
                            if (!paved) {
                                // T-24: 记录拒绝,缺口轮次会把连续失败站落为
                                // PROTECTED_GAP 并停止该 chunk 的重试循环。
                                if (refusals.containsKey(i)) constructionRefused[i] = true;
                                completed[0] = false;
                            }
                            return;
                        }
                        completed[0] = false;
                        return;
                    }
                    SegmentPaver.SegmentGrade grade = access.grade( seg, i, middlePositions, baseYArr, tunnel.active(),
                            spanMasks.preserveGradeAt(i));
                    boolean paved = access.pave( data, seg, i, middlePositions, baseYArr, grade,
                            roadType, materials, slabMaterials, random, cfg, refusals,
                            tunnel.portal());
                    if (!paved) {
                        if (refusals.containsKey(i)) constructionRefused[i] = true;
                        completed[0] = false;
                        // Do not apply a tunnel shell after its road deck or
                        // clearance preflight failed. That created orphaned
                        // tunnel mouths/platforms and disconnected routes.
                        return;
                    }
                    access.apply(tunnel);
                });

                if (cfg.roadAppearance().roadSignsEnabled()) {
                    access.bankSign(
                            decorations,
                            averaged,
                            next,
                            prev,
                            roadWidth,
                            skipSegments,
                            i);
                }
            }

            // Bridge segments use the dedicated guardrail-lantern system.  Do
            // not also run the normal road decoration planner here: doing so
            // places tall roadside lamps on the deck (or below a water span)
            // and duplicates the bridge lighting.
            // T-24: 施工拒绝站不放路灯 —— 失败站没有任何路面,路灯会插进
            // 村庄/地形里(用户观察点 -523,144,-263 的密集路灯即来源于此)。
            if (centerOwned && !spanMasks.directCrossingAt(i) && !isBridge[i]
                    && !spanMasks.tunnelAt(i)
                    && !(constructionRefused != null && i < constructionRefused.length && constructionRefused[i])) {
                // 里程按全路站序共享；仍只给本站读取当前配置，以保持装饰和拒绝分支的顺序。
                int lampInterval = Math.max(1, cfg.lampInterval());
                access.decorate(
                        decorations,
                        averaged,
                        segmentIndex,
                        next,
                        prev,
                        middlePositions,
                        roadWidth,
                        random,
                        buildFrame.stationSpacing().nearestToMultiple(segmentIndex, lampInterval),
                        lampInterval,
                        (roadType == 0 ? DecorationPlanner.Mode.ARTIFICIAL : DecorationPlanner.Mode.NATURAL));
            }
        }
        for(int i=0;i<constructionRefused.length;i++)if(constructionRefused[i])state.refuse(i);
        if(!completed[0])state.fail();
        return completed[0];
    }
    private static boolean contains(ChunkPos chunk,BlockPos pos){
        return pos.getX()>=chunk.getMinBlockX() && pos.getX()<=chunk.getMaxBlockX()
                && pos.getZ()>=chunk.getMinBlockZ() && pos.getZ()<=chunk.getMaxBlockZ();
    }
}
