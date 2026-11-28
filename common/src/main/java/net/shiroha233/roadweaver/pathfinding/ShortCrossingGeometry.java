/* 文件职责：识别两侧有落脚点的短凹地，以及按全段深度选择涵洞或其他跨越。 */
package net.shiroha233.roadweaver.pathfinding;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;

public final class ShortCrossingGeometry {
    private ShortCrossingGeometry() {}

    static SpanType wetType(int width, int stations, int deepest, int maxCulvert, int maxDepth) {
        // 步长变化时既不能把宽河算成短河，也不能把部分浅滩截出来覆盖整条水道。
        if (stations <= maxCulvert && width <= maxCulvert && deepest <= maxDepth) return SpanType.CULVERT;
        return PathSpanExtractor.crossingTypeForWidth(width);
    }

    /** 先定跨越类型再算路面，涵洞不能变成最终水深校验后的补丁。 */
    static void appendPlannedWetSpan(List<RoadSpan> spans, List<BlockPos> centers, int[] ground, int start, int end,
            int waterY, int deepest, int maxCulvert, int maxCulvertDepth, int bridgeMinDepth) {
        int width = PathSpanExtractor.crossingWidthBlocks(centers, start, end);
        var type = ShortCrossingGeometry.wetType(width, end - start + 1, deepest, maxCulvert, maxCulvertDepth);
        if (type == SpanType.CULVERT) {
            // 单湿站用邻接站界定跨度；同岸的路面高度仍由坡度求解决定。
            int first = start == end ? Math.max(0, start - 1) : start;
            int last = first == end ? Math.min(centers.size() - 1, end + 1) : end;
            if (first < last) spans.add(new RoadSpan(centers.get(first), centers.get(last), type));
        } else if (type == SpanType.ROAD_CROSSING || deepest - 1 >= bridgeMinDepth) {
            int[] stable = BridgeDeckProfile.findStableBankRange(ground, start, end, waterY + 1);
            PathSpanExtractor.appendNormalizedWaterSpan(spans, centers, stable[0], stable[1], type);
        }
    }

    public record Notch(int left, int right) {}

    /** 两侧都要有可落脚的岸，沿路站序测量；扫描跨度被短跨门槛封顶。 */
    public static List<Notch> dryNotches(List<BlockPos> centers, int[] ground,
            int drop, boolean[] occupied) {
        List<Notch> result = new ArrayList<>();
        for (int left = 1; left + 3 < ground.length; left++) {
            if (occupied[left] || !stableBank(centers, ground, occupied, left, -1)
                    || ground[left] - ground[left + 1] < drop) continue;
            int highestInside = ground[left + 1];
            for (int right = left + 2; right + 1 < ground.length
                    && right <= left + PathSpanExtractor.ROAD_CROSSING_MAX_WIDTH_BLOCKS; right++) {
                if (occupied[right - 1] || occupied[right]) break;
                int bank = Math.min(ground[left], ground[right]);
                if (bank - highestInside >= drop && ground[right] - ground[right - 1] >= drop) {
                    int width = PathSpanExtractor.crossingWidthBlocks(centers, left + 1, right - 1);
                    if (stableBank(centers, ground, occupied, right, 1)
                            && width <= PathSpanExtractor.ROAD_CROSSING_MAX_WIDTH_BLOCKS) {
                        // 宽度决定是否直接跨越；深度只影响下面空多少，不能让道路降回谷底。
                        result.add(new Notch(left, right));
                        left = right - 1;
                    }
                    break;
                }
                highestInside = Math.max(highestInside, ground[right]);
                if (ground[left] - highestInside < drop) break;
            }
        }
        return result;
    }

    private static boolean stableBank(List<BlockPos> centers, int[] ground, boolean[] occupied, int edge, int direction) {
        // 单列和两列的小尖峰都不能冒充岸：至少三格连续稳地；粗步长大时仍按实际水平格距检查。
        double run = 0;
        int previous = edge;
        for (int at = edge + direction; at >= 0 && at < ground.length; at += direction) {
            if (occupied[at]) return false;
            double step = PathSpanExtractor.horizontalDistance(centers.get(previous), centers.get(at));
            if (Math.abs(ground[previous] - ground[at]) > Math.max(1.0, step * 0.25)) return false;
            run += step;
            if (Math.abs(ground[edge] - ground[at]) > Math.max(1.0, run * 0.25)) return false;
            if (run >= 3) return true;
            previous = at;
        }
        return false;
    }
}
