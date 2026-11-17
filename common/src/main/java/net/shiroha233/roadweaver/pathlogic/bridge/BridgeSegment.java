/* 文件职责：桥梁段数据结构。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.world.phys.Vec3;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.util.Line;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

import java.util.*;

/**
 * 桥梁段数据结构
 */
public class BridgeSegment {
    // 每站只存曲线引用；运行期掩码由区块持有，不跨区块缓存可变结果。
    private final Line[] linesByStation;
    private final boolean[] landing;

    public BridgeSegment(boolean[] isBridge, List<RoadSegmentPlacement> segments) {
        this(isBridge, segments, null);
    }

    public BridgeSegment(boolean[] isBridge, List<RoadSegmentPlacement> segments, int[] targetY) {
        linesByStation = new Line[segments.size()];
        landing = new boolean[segments.size()];
        for (int i = 0; i < segments.size();) {
            if (!isBridge[i]) { i++; continue; }
            int first = i;
            while (i < segments.size() && isBridge[i]) i++;
            int last = i - 1;
            Vec3 start = center(segments, targetY, first);
            Vec3 end = center(segments, targetY, last);
            double length = Math.sqrt((end.x - start.x) * (end.x - start.x)
                    + (end.z - start.z) * (end.z - start.z));
            double offset = 0.0D;
            if (length >= RoadConstants.BRIDGE_CURVE_MIN_LENGTH_BLOCKS) {
                long hash = Double.doubleToLongBits(start.x) * 31L
                        + Double.doubleToLongBits(start.z) * 17L
                        + Double.doubleToLongBits(end.x) * 13L
                        + Double.doubleToLongBits(end.z);
                offset = (hash & 1L) == 0L
                        ? RoadConstants.BRIDGE_CURVE_MAX_OFFSET_BLOCKS
                        : -RoadConstants.BRIDGE_CURVE_MAX_OFFSET_BLOCKS;
            }
            var line = Line.withHorizontalBend(start, end, offset);
            // 连续桥段共享同一条曲线，查询不再遍历所有桥段或装箱站号。
            Arrays.fill(linesByStation, first, last + 1, line);
            landing[first] = true;
            landing[last] = true;
        }
    }

    /**
     * 通过索引获取桥梁曲线
     * 
     * @param index 路段索引
     * @return 对应的桥梁曲线，如果不存在则返回 null
     */
    public Line getLine(int index) {
        return index >= 0 && index < linesByStation.length ? linesByStation[index] : null;
    }

    public boolean isLanding(int index) {
        return index >= 0 && index < landing.length && landing[index];
    }
    /** 保留旧中心点与短目标数组的逐站替换语义，不能把方块中心Y偏移混进持久目标Y。 */
    private static Vec3 center(List<RoadSegmentPlacement> segments, int[] targetY, int i) {
        Vec3 point = segments.get(i).middlePos().getCenter();
        return targetY != null && i < targetY.length ? new Vec3(point.x, targetY[i], point.z) : point;
    }

}
