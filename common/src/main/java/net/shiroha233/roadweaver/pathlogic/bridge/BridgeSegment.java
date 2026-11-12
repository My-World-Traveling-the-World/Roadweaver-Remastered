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
    private final Map<Set<Integer>, Line> bridgeLines = new HashMap<>();
    private final Set<Integer> landingIndices = new HashSet<>();

    public BridgeSegment(boolean[] isBridge, List<RoadSegmentPlacement> segments) {
        this(isBridge, segments, null);
    }

    public BridgeSegment(boolean[] isBridge, List<RoadSegmentPlacement> segments, int[] targetY) {
        List<List<Vec3>> list1 = new ArrayList<>();
        List<Set<Integer>> list2 = new ArrayList<>();
        list1.add(new ArrayList<>());
        list2.add(new HashSet<>());
        
        for (int i = 0; i < segments.size(); i++) {
            if (isBridge[i]) {
                Vec3 center = segments.get(i).middlePos().getCenter();
                if (targetY != null && i < targetY.length) {
                    center = new Vec3(center.x, targetY[i], center.z);
                }
                list1.get(list1.size() - 1).add(center);
                list2.get(list1.size() - 1).add(i);
            } else {
                if (!list1.get(list1.size() - 1).isEmpty()) {
                    list1.add(new ArrayList<>());
                    list2.add(new HashSet<>());
                }
            }
        }
        
        if (list1.get(list1.size() - 1).isEmpty()) {
            list1.remove(list1.size() - 1);
            list2.remove(list2.size() - 1);
        }

        for (int i = 0; i < list1.size(); i++) {
            List<Vec3> seg = list1.get(i);
            Vec3 start = seg.get(0);
            Vec3 end = seg.get(seg.size() - 1);
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
            Set<Integer> indices = list2.get(i);
            bridgeLines.put(indices, line);
            if (!indices.isEmpty()) {
                landingIndices.add(Collections.min(indices));
                landingIndices.add(Collections.max(indices));
            }
        }
    }

    /**
     * 通过索引获取桥梁曲线
     * 
     * @param index 路段索引
     * @return 对应的桥梁曲线，如果不存在则返回 null
     */
    public Line getLine(int index) {
        for (Map.Entry<Set<Integer>, Line> entry : bridgeLines.entrySet()) {
            if (entry.getKey().contains(index)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public boolean isLanding(int index) {
        return landingIndices.contains(index);
    }
}
