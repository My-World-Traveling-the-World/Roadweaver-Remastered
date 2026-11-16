/* 文件职责：观景穹顶 —— 风景段的玻璃穹顶生成。 */
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
import static net.shiroha233.roadweaver.pathlogic.tunnel.PortalSafetyRules.*;
import static net.shiroha233.roadweaver.pathlogic.tunnel.PlannedSectionBuilder.*;

/**
 * 观景穹顶。
 *
 * <h2>它是什么</h2>
 * <p>隧道走到风景好的地方（{@code scenicProgress} 判「走到够长的连续段」），
 * 给这一段加一个玻璃穹顶 —— 玩家在隧道里也能看到外面的景色。</p>
 *
 * <h2>★ 为什么要有「水不封顶」这条</h2>
 * <p>穹顶如果用玻璃封顶，建在水下就会变成「水墙压着玻璃」，观感很怪、
 * 而且玻璃下方会渗水。所以 {@code containsWater} 为真时**不长穹顶**。
 * 这条判据是纯观感的，但它决定了整段要不要走穹顶分支。</p>
 *
 * <p>本类是从 1521 行的 {@code RoadTunnelBuilder} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
final class ScenicDomeBuilder {
    static boolean containsWater(WorldGenLevel world, List<BlockPos> positions) {
        if (world == null || positions == null) return false;
        for (BlockPos position : positions) {
            if (position != null && TunnelReadTiming.block(world, position).getFluidState().is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** Longitudinal length of a plan's interior, for normalising the glass gradient. */
    static double scenicSpanLength(TunnelPlan plan) {
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
    static double scenicProgress(BlockPos column, BlockPos start, double spanLength) {
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
    static BlockState scenicGlassState(double progress) {
        return ScenicGlassShell.glass(progress);
    }

    static void placeScenicDome(WorldGenLevel world, TunnelPlan plan,
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
}
