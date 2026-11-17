/* 文件职责：判定一条路是否与某个区块相交（世界生成只编译相交的区块）。 */
package net.shiroha233.roadweaver.worldgen.road;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.List;

/**
 * 区块相交判定。
 *
 * <h2>为什么单独成文件</h2>
 * <p>世界生成按区块触发：只有与某区块相交的道路才需要被编译进那个区块的计划。
 * 这个判定<b>与计划编译本身是两件事</b> —— 前者只做几何相交测试，后者要生成
 * 切片、栅格段、占用掩码。原先两者混在一个 1048 行的文件里，超过本仓
 * 「一个文件不得超过千行」的硬约束；拆开后本类不依赖 {@code RoadData}，
 * 只吃 {@code List<RoadSegmentPlacement>}，可以独立测试。</p>
 *
 * <h2>为什么有五个相似的方法</h2>
 * <p>它们回答的是<b>不同粒度</b>的问题，历史上逐个加进来各有用途：</p>
 * <ul>
 *   <li>{@code intersectsChunk} —— 单个 footprint 与区块是否相交（精确）</li>
 *   <li>{@code anyPlacementIntersectsChunk} —— 任一 footprint 命中即可（早退，快）</li>
 *   <li>{@code roadTouchesChunk} —— 整条路是否碰到区块（用于决定要不要编译）</li>
 *   <li>{@code segmentIntersectsChunk} —— 两点连线段是否穿过区块（诊断用）</li>
 *   <li>{@code countMaskHits} —— 某个 span 掩码在区块内命中几站（诊断用）</li>
 * </ul>
 *
 * @see RoadChunkPlanCompiler
 */
final class RoadChunkIntersection {

    private RoadChunkIntersection() {}

    static boolean segmentIntersectsChunk(BlockPos from, BlockPos to,
                                                  int minX, int minZ, int maxX, int maxZ) {
        if (from == null || to == null) return false;
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int samples = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(from.getX() + dx * t);
            int z = (int) Math.round(from.getZ() + dz * t);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) return true;
        }
        return false;
    }

    /** 诊断区块内跨度掩码站数，不以站数证明方块已写入。 */
    static int countMaskHits(List<RoadSegmentPlacement> placements,
                                     ChunkPos chunkPos,
                                     boolean[] mask) {
        if (mask == null) return 0;
        int hits = 0;
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxX = chunkPos.getMaxBlockX();
        int maxZ = chunkPos.getMaxBlockZ();
        for (int i = 0; i < placements.size() && i < mask.length; i++) {
            if (!mask[i]) continue;
            RoadSegmentPlacement placement = placements.get(i);
            if (placement == null || placement.middlePos() == null) continue;
            int x = placement.middlePos().getX();
            int z = placement.middlePos().getZ();
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) hits++;
        }
        return hits;
    }

    /** 判断完整铺装占地是否碰区块，不能只看中心站所属区块。 */
    static boolean anyPlacementIntersectsChunk(List<RoadSegmentPlacement> placements,
                                                       ChunkPos chunkPos) {
        for (RoadSegmentPlacement placement : placements) {
            if (placement == null) continue;
            if (intersectsChunk(placement,
                    chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                    chunkPos.getMaxBlockX(), chunkPos.getMaxBlockZ(), 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * T-20C: 单遍位置预筛。一条道路可能影响本 chunk 的全部途径及其上界:
     * <ul>
     *   <li>站点触达(localIndices)——margin 0,桥模板站 margin
     *       {@value #RoadChunkPlanCompiler.BRIDGE_TEMPLATE_MARGIN_BLOCKS};</li>
     *   <li>占用标记(markOccupied)——足迹列在 chunk 内,或中点圆盘半径
     *       min(16, halfWidth+1);</li>
     *   <li>密度光栅(rasterSegments)——中点对 bbox 外扩 halfWidth+RoadChunkPlanCompiler.BANK_WIDTH。</li>
     * </ul>
     * 预筛 margin 取上述上界,因此被拒绝的道路对这些产物的影响全部为空,
     * 真正相交 chunk 的编译结果不变。单次 O(站位+足迹+邻接边采样)。
     */
    static boolean roadTouchesChunk(List<RoadSegmentPlacement> placements,
                                            ChunkPos chunkPos, int halfWidth) {
        int margin = Math.max(RoadChunkPlanCompiler.BRIDGE_TEMPLATE_MARGIN_BLOCKS, halfWidth + RoadChunkPlanCompiler.BANK_WIDTH + 1);
        int minBoundaryX = chunkPos.getMinBlockX() - margin;
        int minBoundaryZ = chunkPos.getMinBlockZ() - margin;
        int maxBoundaryX = chunkPos.getMaxBlockX() + margin;
        int maxBoundaryZ = chunkPos.getMaxBlockZ() + margin;
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int chunkMaxX = chunkPos.getMaxBlockX();
        int chunkMaxZ = chunkPos.getMaxBlockZ();
        BlockPos previousMiddle = null;
        for (RoadSegmentPlacement placement : placements) {
            if (placement == null) {
                previousMiddle = null;
                continue;
            }
            BlockPos middle = placement.middlePos();
            if (middle != null
                    && middle.getX() >= minBoundaryX && middle.getX() <= maxBoundaryX
                    && middle.getZ() >= minBoundaryZ && middle.getZ() <= maxBoundaryZ) {
                return true;
            }
            List<BlockPos> positions = placement.positions();
            if (positions != null) {
                for (BlockPos position : positions) {
                    if (position != null
                            && position.getX() >= chunkMinX && position.getX() <= chunkMaxX
                            && position.getZ() >= chunkMinZ && position.getZ() <= chunkMaxZ) {
                        return true;
                    }
                }
            }
            if (previousMiddle != null && middle != null
                    && segmentIntersectsChunk(previousMiddle, middle,
                    minBoundaryX, minBoundaryZ, maxBoundaryX, maxBoundaryZ)) {
                return true;
            }
            previousMiddle = middle;
        }
        return false;
    }

    static boolean intersectsChunk(RoadSegmentPlacement placement,
                                           int minX, int minZ, int maxX, int maxZ,
                                           int margin) {        int safeMargin = Math.max(0, margin);
        int queryMinX = minX - safeMargin;
        int queryMinZ = minZ - safeMargin;
        int queryMaxX = maxX + safeMargin;
        int queryMaxZ = maxZ + safeMargin;
        if (placement.positions() != null) {
            for (BlockPos position : placement.positions()) {
                if (position != null && position.getX() >= queryMinX && position.getX() <= queryMaxX
                        && position.getZ() >= queryMinZ && position.getZ() <= queryMaxZ) {
                    return true;
                }
            }
        }
        BlockPos middle = placement.middlePos();
        return middle != null && middle.getX() >= queryMinX && middle.getX() <= queryMaxX
                && middle.getZ() >= queryMinZ && middle.getZ() <= queryMaxZ;
    }

}
