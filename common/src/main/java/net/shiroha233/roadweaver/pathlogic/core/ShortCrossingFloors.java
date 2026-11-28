/* 文件职责：在纵断面求解前，将两侧落脚点与水面转成短跨的高程窗口。 */
package net.shiroha233.roadweaver.pathlogic.core;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;

final class ShortCrossingFloors {
    private ShortCrossingFloors() {}

    static int[] calculate(List<Road.SpanRange> ranges, int[] ground, boolean[] bridge, IntUnaryOperator waterY) {
        if (ranges.stream().noneMatch(range -> range.type() != SpanType.BRIDGE)) return null;
        int[] result = new int[ground.length];
        Arrays.fill(result, Integer.MIN_VALUE);
        for (var range : ranges) {
            if (range.type() == SpanType.BRIDGE) continue;
            int start = range.start(), end = range.end(), firstWet = -1, lastWet = -1;
            int highest = Integer.MIN_VALUE;
            for (int i = start; i <= end; i++) {
                int water = waterY.applyAsInt(i);
                if (water == Integer.MIN_VALUE) continue;
                if (firstWet < 0) firstWet = i;
                lastWet = i;
                highest = Math.max(highest, water);
            }
            // 干沟的跨度本身包含岸边；水跨则用真实湿段之外的岸边，避免把干岸抬到整段最高水位。
            int from = firstWet >= 0 ? firstWet : start + 1;
            int to = firstWet >= 0 ? lastWet : end - 1;
            if (from > to) continue;
            int leftIndex = Math.max(0, from - 1), rightIndex = Math.min(ground.length - 1, to + 1);
            int waterFloor = firstWet < 0 ? Integer.MIN_VALUE : highest + Road.FORD_DECK_CLEARANCE;
            if (firstWet >= 0) {
                var stable = BridgeDeckProfile.findStableBankRange(ground, from, to, highest + 1);
                if (stable[0] < from) leftIndex = stable[0];
                if (stable[1] > to) rightIndex = stable[1];
            }
            int left = ground[leftIndex], right = ground[rightIndex];
            for (int i = from; i <= to; i++) {
                if (bridge[i]) continue;
                // 插值只提供下界，允许平缓分摊两岸高差；不把每一站硬钉死。
                int bank = left + (int) Math.round((right - left) * (i - leftIndex)
                        / Math.max(1.0, rightIndex - leftIndex));
                result[i] = Math.max(result[i], Math.max(bank, waterFloor));
            }
        }
        return result;
    }
}
