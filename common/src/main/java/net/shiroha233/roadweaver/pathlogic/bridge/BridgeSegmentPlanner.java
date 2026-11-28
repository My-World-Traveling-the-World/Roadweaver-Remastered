/* 文件职责：桥梁段规划器。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.util.Line;

import java.util.List;

/**
 * 桥梁段规划器
 */
public final class BridgeSegmentPlanner {
    private BridgeSegmentPlanner() {
    }

    public static final class Context {
        public boolean insideBridgeRange = false;
        public int currentRangeEnd = -1;
        public Integer lastBridgeDeckY = null;
    }

    public static Context newContext() {
        return new Context();
    }

    public static boolean processSegment(WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            BlockPos middle,
            BlockPos prev,
            BlockPos next,
            int roadWidth,
            int baseYForThis,
            int deckY,
            int segmentIndex,
            RandomSource random,
            ModConfig cfg,
            List<int[]> bridgeRanges,
            int[] baseYArr,
            int i,
            Context ctx) {
        return processSegment(world, road, seg, middle, prev, next, roadWidth, baseYForThis, deckY,
                segmentIndex, random, cfg, bridgeRanges, baseYArr, i, ctx, null);
    }

    public static boolean processSegment(WorldGenLevel world,
            RoadData road,
            RoadSegmentPlacement seg,
            BlockPos middle,
            BlockPos prev,
            BlockPos next,
            int roadWidth,
            int baseYForThis,
            int deckY,
            int segmentIndex,
            RandomSource random,
            ModConfig cfg,
            List<int[]> bridgeRanges,
            int[] baseYArr,
            int i,
            Context ctx,
            Line geometryLine) {
        // Road.generateRoad has already encoded bridge deck and approach
        // elevations into RoadData.targetY.  Recomputing them per generated
        // chunk used to make decks, ramps, shoulders, and normal pavement
        // disagree at chunk borders.
        //
        // 纵断面是脚底高度；桥梁与普通整砖路都把实体块铺在它的下一层。
        int segDeckY = baseYForThis;
        boolean placePier = true;
        boolean placeRail = true;
        int rangeStart = i;
        int rangeEnd = i;
        if (!bridgeRanges.isEmpty()) {
            for (int[] r : bridgeRanges) {
                if (i >= r[0] && i <= r[1]) {
                    rangeStart = r[0];
                    rangeEnd = r[1];
                    if (i == r[0] || i == r[1]) {
                        placeRail = false;
                    }
                    break;
                }
            }
        }

        BridgeMaterialPalette palette = BridgeMaterialPalette.forRange(road, rangeStart, rangeEnd);
        // Runtime water detection may extend a bridge range as neighboring
        // chunks become available. Anchor the repeating body to the persisted
        // route index, never to that mutable range boundary.
        int bridgePhase = phaseForSegment(i);

        return BridgeBuilder.placeSegment(world, road, seg, middle, prev, next, roadWidth, segDeckY, bridgePhase,
                segmentIndex, random, cfg, placePier, placeRail, palette, geometryLine);
    }

    static int phaseForSegment(int segmentIndex) {
        return Math.floorMod(segmentIndex, 7);
    }
}
