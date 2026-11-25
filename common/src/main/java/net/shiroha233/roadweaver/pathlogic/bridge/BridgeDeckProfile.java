/* 文件职责：桥面纵断面：桥面高度沿跨径的取值曲线。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import java.util.List;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.Arrays;
import java.util.Comparator;

/** 统一桥面标高与陆侧接近坡，避免各区块单独定高。 */
public final class BridgeDeckProfile {
    private static final int STABLE_SEARCH_SEGMENTS = 32;
    private static final int STABLE_WINDOW_SEGMENTS = 4;
    private static final int STABLE_WINDOW_VARIATION = 2;
    private static final int MAX_ADAPTIVE_RAMP_SEGMENTS = 30;

    /**
     * 0.9.066（B2）：相邻 BRIDGE 跨度之间的普通段小于等于这个站数时，视为「同一片水被切成
     * 两段」，后跨甲板不得低于前跨，消除两段桥之间的台阶。超过则视为独立过水，互不约束。
     */
    public static final int BRIDGE_SPAN_CONTINUITY_GAP = 24;

    private BridgeDeckProfile() {
    }

    /** 持久跨度已选定岸端，施工直接消费同一范围，不再次搜索河岸。
 *
     * Applies one persisted crossing range without searching for its banks a
     * second time. The caller already resolved stable land endpoints during
     * planning, so every generated chunk receives the same deck and ramps.
     */
    public static void applyFixedRange(int[] profile,
                                       int start,
                                       int end,
                                       int minimumDeckY,
                                       int rampSegments) {
        if (profile == null || profile.length == 0) return;
        int safeStart = Math.max(0, Math.min(start, profile.length - 1));
        int safeEnd = Math.max(safeStart, Math.min(end, profile.length - 1));
        int[] original = profile.clone();
        int leftY = original[safeStart];
        int rightY = original[safeEnd];
        int span = Math.max(1, safeEnd - safeStart);
        for (int i = safeStart; i <= safeEnd; i++) {
            double progress = (i - safeStart) / (double) span;
            int bankGrade = (int) Math.round(leftY + (rightY - leftY) * progress);
            profile[i] = Math.max(minimumDeckY, bankGrade);
        }

        int configuredRamps = Math.max(0, rampSegments);
        int leftDifference = safeStart > 0
                ? Math.abs(profile[safeStart] - original[safeStart - 1]) : 0;
        int rightDifference = safeEnd + 1 < profile.length
                ? Math.abs(profile[safeEnd] - original[safeEnd + 1]) : 0;
        int leftRamps = Math.min(MAX_ADAPTIVE_RAMP_SEGMENTS,
                Math.max(configuredRamps, leftDifference));
        int rightRamps = Math.min(MAX_ADAPTIVE_RAMP_SEGMENTS,
                Math.max(configuredRamps, rightDifference));
        int left = Math.max(0, safeStart - leftRamps);
        int right = Math.min(profile.length - 1, safeEnd + rightRamps);
        for (int i = left; i < safeStart; i++) {
            double progress = (i - left + 1D) / (safeStart - left + 1D);
            profile[i] = (int) Math.round(original[left]
                    + (profile[safeStart] - original[left]) * progress);
        }
        for (int i = safeEnd + 1; i <= right; i++) {
            double progress = (right - i + 1D) / (right - safeEnd + 1D);
            profile[i] = (int) Math.round(original[right]
                    + (profile[safeEnd] - original[right]) * progress);
        }
    }

    /**
     * 0.9.058（缺陷 A）：一个跨度的**甲板基准**（{@code minimumDeck}）。
     *
     * <p>两条分支都必须留着：</p>
     * <ul>
     *   <li><b>有水</b>（{@code highestWaterY != Integer.MIN_VALUE}）⇒ {@code 水面 + clearance}。
     *       这是「桥面不许泡在水里」的唯一定义；</li>
     *   <li><b>无水</b>（干峡谷）⇒ {@code max(两端站Y) + clearance}。0.9.05x 的干峡谷分支
     *       逐位保留：没有水面可循时，两岸自己的规划高度就是基准。**绝不能**退化成 0
     *       或任何常量 —— 那会把深干峡谷的甲板按常量乱抬/乱压。</li>
     * </ul>
     *
     * <p>纯函数，不读配置、不读世界；{@code deckClearance < 1} 时按 1 处理（与调用方
     * {@code Math.max(1, cfg.bridgeDeckClearance())} 同口径）。</p>
     */
    public static int minimumDeckY(int highestWaterY, int bankStartY, int bankEndY, int deckClearance) {
        int clearance = Math.max(1, deckClearance);
        if (highestWaterY == Integer.MIN_VALUE) {
            return Math.max(bankStartY, bankEndY) + clearance;
        }
        return highestWaterY + clearance;
    }

    public static void applyRanges(int[] heights, List<int[]> ranges, int deckY, int rampSegments) {
        if (heights == null || heights.length == 0 || ranges == null || ranges.isEmpty()) return;
        boolean[] bridge = new boolean[heights.length];
        for (int[] range : ranges) {
            if (range == null || range.length < 2) continue;
            int start = Math.max(0, Math.min(range[0], range[1]));
            int end = Math.min(heights.length - 1, Math.max(range[0], range[1]));
            for (int index = start; index <= end; index++) bridge[index] = true;
        }
        applyMask(heights, bridge, deckY, rampSegments);
    }

    public static void applyMask(int[] heights, boolean[] bridge, int deckY, int rampSegments) {
        if (heights == null || bridge == null || heights.length == 0 || heights.length != bridge.length) return;
        // 0.9.037（离线补测抓出的真 bug）：原来引道基准直接读 heights[left] / heights[right]，
        // 也就是**已经被上一次调用改过**的数组。同一数组上应用两次，引道每站会再偏移 1 格
        // （实测 70→69→68），桥头被一点点啃出台阶。applyFixedRange 一直是先 clone 再读基准，
        // applyMask 漏了这一步，两者不一致。
        //
        // 修法：先快照。对「首次调用」逐位不变（此时 original 与 heights 内容相同）；
        // 只有「同一数组被重复应用」以及「相邻两段桥的引道互相重叠」两种情形会变，
        // 而这两种情形下读调用前的剖面才是正确语义（与 applyFixedRange 一致）。
        int[] original = heights.clone();
        int index = 0;
        while (index < heights.length) {
            while (index < heights.length && !bridge[index]) index++;
            int start = index;
            while (index < heights.length && bridge[index]) index++;
            int end = index - 1;
            if (start > end) continue;

            int ramps = Math.max(0, rampSegments);
            int left = Math.max(0, start - ramps);
            int right = Math.min(heights.length - 1, end + ramps);
            int leftBase = original[left];
            int rightBase = original[right];

            for (int i = start; i <= end; i++) heights[i] = deckY;
            if (ramps == 0) continue;

            for (int i = left; i < start; i++) {
                if (bridge[i]) continue;
                double progress = (i - left + 1D) / (start - left + 1D);
                heights[i] = (int) Math.round(leftBase + (deckY - leftBase) * progress);
            }
            for (int i = end + 1; i <= right; i++) {
                if (bridge[i]) continue;
                double progress = (right - i + 1D) / (right - end + 1D);
                heights[i] = (int) Math.round(rightBase + (deckY - rightBase) * progress);
            }
        }
    }

    /** 由稳定岸端连线控制桥面，最低处仍满足甲板下界，不能沿河床起伏。
 *
     * Spans a river or canyon between stable inland road heights. The bridge
     * never follows the riverbed; unequal banks are joined by a smooth linear
     * grade whose lowest point remains above the requested minimum deck.
     */
    public static void applyStableBankMask(int[] profile,
                                           int[] terrain,
                                           boolean[] bridge,
                                           int minimumDeckY) {
        if (profile == null || terrain == null || bridge == null
                || profile.length == 0 || profile.length != terrain.length
                || profile.length != bridge.length) return;
        java.util.ArrayList<int[]> sourceRanges = new java.util.ArrayList<>();
        int index = 0;
        while (index < bridge.length) {
            while (index < bridge.length && !bridge[index]) index++;
            int start = index;
            while (index < bridge.length && bridge[index]) index++;
            int end = index - 1;
            if (start <= end) sourceRanges.add(new int[]{start, end});
        }
        for (int[] range : sourceRanges) {
            int start = range[0];
            int end = range[1];
            int[] stable = findStableBankRange(terrain, start, end, minimumDeckY);
            int left = stable[0];
            int right = stable[1];
            int leftY = stableHeight(terrain, left, true);
            int rightY = stableHeight(terrain, right, false);
            int span = Math.max(1, right - left);
            for (int i = left; i <= right; i++) {
                double progress = (i - left) / (double) span;
                int interpolated = (int) Math.round(leftY + (rightY - leftY) * progress);
                profile[i] = Math.max(minimumDeckY, interpolated);
                bridge[i] = true;
            }
        }
    }

    /**
     * 0.9.066（B2）：同一连接上相邻 BRIDGE 跨度甲板连续性。
     *
     * <p>{@code crossingRanges} 按 spans 顺序添加、不一定按站号排序。这里先按 {@code start}
     * 排序，再把 gap &lt;= {@link #BRIDGE_SPAN_CONTINUITY_GAP} 的相邻 BRIDGE 跨度视为「同一片水
     * 被切成两段」，后跨甲板不得低于前跨 —— 消除两段桥之间的台阶（真机曾见相邻跨度甲板
     * 71 -&gt; 73 这类跳变）。超过 gap 的视为独立过水，互不约束。</p>
     *
     * <p>纯函数：就地调整 {@code deckByRange}，不改其它数组；{@code deckByRange[i]} 与
     * {@code types[i]}/{@code starts[i]}/{@code ends[i]} 按同一 index 对应。</p>
     */
    public static void enforceAdjacentBridgeContinuity(int[] deckByRange, SpanType[] types,
                                                       int[] starts, int[] ends) {
        if (deckByRange == null || types == null || starts == null || ends == null) return;
        int n = Math.min(Math.min(deckByRange.length, types.length),
                Math.min(starts.length, ends.length));
        if (n == 0) return;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(i -> starts[i]));
        int prevDeck = Integer.MIN_VALUE;
        int prevEnd = Integer.MIN_VALUE;
        for (int i : order) {
            if (types[i] != SpanType.BRIDGE) continue;
            if (prevEnd != Integer.MIN_VALUE
                    && starts[i] - prevEnd <= BRIDGE_SPAN_CONTINUITY_GAP
                    && deckByRange[i] < prevDeck) {
                deckByRange[i] = prevDeck;
            }
            // 同组低桥已抬高，这里直接记本桥即可；远距离断组时必须忘掉旧水域的高甲板。
            prevDeck = deckByRange[i];
            prevEnd = ends[i];
        }
    }

    public static void applyStableBankRanges(int[] profile,
                                             int[] terrain,
                                             List<int[]> ranges,
                                             int minimumDeckY) {
        if (profile == null || terrain == null || profile.length != terrain.length
                || ranges == null || ranges.isEmpty()) return;
        boolean[] bridge = new boolean[profile.length];
        for (int[] range : ranges) {
            if (range == null || range.length < 2) continue;
            int start = Math.max(0, Math.min(range[0], range[1]));
            int end = Math.min(profile.length - 1, Math.max(range[0], range[1]));
            for (int i = start; i <= end; i++) bridge[i] = true;
        }
        applyStableBankMask(profile, terrain, bridge, minimumDeckY);
    }

    public static int[] findStableBankRange(int[] terrain,
                                            int waterStart,
                                            int waterEnd,
                                            int minimumBankY) {
        if (terrain == null || terrain.length == 0) return new int[]{0, 0};
        int start = Math.max(0, Math.min(waterStart, terrain.length - 1));
        int end = Math.max(start, Math.min(waterEnd, terrain.length - 1));
        int left = findStableLeft(terrain, start - 1, minimumBankY);
        int right = findStableRight(terrain, end + 1, minimumBankY);
        if (left < 0) left = start;
        if (right < 0) right = end;
        if (left > start) left = start;
        if (right < end) right = end;
        return new int[]{left, right};
    }

    private static int findStableLeft(int[] terrain, int from, int minimumBankY) {
        int lower = Math.max(0, from - STABLE_SEARCH_SEGMENTS + 1);
        for (int edge = Math.min(from, terrain.length - 1); edge >= lower; edge--) {
            int windowStart = edge - STABLE_WINDOW_SEGMENTS + 1;
            if (windowStart < 0) continue;
            if (stableWindow(terrain, windowStart, edge, minimumBankY)) return edge;
        }
        return -1;
    }

    private static int findStableRight(int[] terrain, int from, int minimumBankY) {
        int upper = Math.min(terrain.length - 1, from + STABLE_SEARCH_SEGMENTS - 1);
        for (int edge = Math.max(0, from); edge <= upper; edge++) {
            int windowEnd = edge + STABLE_WINDOW_SEGMENTS - 1;
            if (windowEnd >= terrain.length) continue;
            if (stableWindow(terrain, edge, windowEnd, minimumBankY)) return edge;
        }
        return -1;
    }

    private static boolean stableWindow(int[] terrain, int start, int end, int minimumBankY) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = start; i <= end; i++) {
            min = Math.min(min, terrain[i]);
            max = Math.max(max, terrain[i]);
            if (i > start && Math.abs(terrain[i] - terrain[i - 1]) > 1) return false;
        }
        return max - min <= STABLE_WINDOW_VARIATION && median(terrain, start, end) >= minimumBankY;
    }

    private static int stableHeight(int[] terrain, int edge, boolean left) {
        int start = left ? Math.max(0, edge - STABLE_WINDOW_SEGMENTS + 1) : edge;
        int end = left ? edge : Math.min(terrain.length - 1, edge + STABLE_WINDOW_SEGMENTS - 1);
        return median(terrain, start, end);
    }

    private static int median(int[] values, int start, int end) {
        int[] copy = java.util.Arrays.copyOfRange(values, start, end + 1);
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return (copy.length & 1) == 0
                ? (int) Math.round((copy[middle - 1] + copy[middle]) / 2.0D)
                : copy[middle];
    }
}
