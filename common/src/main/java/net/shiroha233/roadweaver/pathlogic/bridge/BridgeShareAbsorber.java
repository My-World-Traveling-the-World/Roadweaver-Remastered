package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨路桥「共享跨越」的**吸附计算**（0.9.029 / R-03）。纯数组运算，无世界访问。
 *
 * <p>输入是**次路**的 spans + 站序列 + 纵断面，以及**主人桥**的 deckY；输出是
 * 「把这段 BRIDGE 换成 NETWORK_REUSE（复用主人甲板）」后的新 spans 与新 targetY。
 *
 * <p><b>绝不制造断崖</b>，因此有两条硬闸：
 * <ol>
 *   <li>被吸收区间内 targetY 一律取主人 deckY；区间外侧按**每站最多 1 格**向外过渡</li>
 *   <li>两侧站数不够把差值过渡回去（会超出数组边界）⇒ <b>整体放弃</b>，返回 {@code null}，
 *       调用方保留两座桥、什么都不改。**最差结果 = 保持现状**</li>
 * </ol>
 *
 * <p>判定该不该吸附是 {@link BridgeShareDetector} 的事；这里只负责「怎么改」和「改不了就放弃」。
 */
public final class BridgeShareAbsorber {

    /** 过渡段每站允许的最大高差（与 {@code MAX_HEIGHT_STEP} 一致）。 */
    public static final int MAX_TRANSITION_PER_STATION = 1;

    private BridgeShareAbsorber() {}

    /**
     * @return 新 targetY；两侧过渡不足（无法在 ≤1 格/站内回到原剖面）返回 {@code null} = 放弃吸附
     */
    public static int[] blendToDeckY(List<Integer> targetY, int fromStation, int toStation, int deckY) {
        if (targetY == null || targetY.isEmpty()) return null;
        int n = targetY.size();
        int from = Math.max(0, Math.min(n - 1, Math.min(fromStation, toStation)));
        int to = Math.max(0, Math.min(n - 1, Math.max(fromStation, toStation)));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = targetY.get(i);

        // 先确认两侧都能收敛，再动手 —— 否则半改会留下更糟的剖面
        if (stationsNeededToConverge(out, from, to, deckY, -1) > from) return null;
        if (stationsNeededToConverge(out, from, to, deckY, +1) > (n - 1 - to)) return null;

        for (int i = from; i <= to; i++) out[i] = deckY;
        blendSide(out, from, to, deckY, -1);
        blendSide(out, from, to, deckY, +1);
        return out;
    }

    /** 从 {@code from}（dir=-1）或 {@code to}（dir=+1）向外，每站最多挪 1 格，直到与原剖面相接。 */
    private static void blendSide(int[] out, int from, int to, int deckY, int dir) {
        int n = out.length;
        int index = dir < 0 ? from - 1 : to + 1;
        int step = 1;
        while (index >= 0 && index < n) {
            int original = out[index];
            if (original == deckY) break;
            int lo = deckY - step * MAX_TRANSITION_PER_STATION;
            int hi = deckY + step * MAX_TRANSITION_PER_STATION;
            int blended = Math.max(lo, Math.min(hi, original));
            out[index] = blended;
            if (blended == original) break;
            index += dir;
            step++;
        }
    }

    /** 需要多少站才能从 deckY 收敛回原剖面（只算方向 dir 一侧）。 */
    private static int stationsNeededToConverge(int[] values, int from, int to, int deckY, int dir) {
        int index = dir < 0 ? from - 1 : to + 1;
        int step = 1;
        while (index >= 0 && index < values.length) {
            int original = values[index];
            if (original == deckY) return step - 1;
            int lo = deckY - step * MAX_TRANSITION_PER_STATION;
            int hi = deckY + step * MAX_TRANSITION_PER_STATION;
            if (original >= lo && original <= hi) return step;
            index += dir;
            step++;
        }
        // 走到头还没收敛 ⇒ 需要超过可用站数
        return Integer.MAX_VALUE;
    }

    /** 把第 {@code index} 个 span 换成 {@code NETWORK_REUSE}（复用主人甲板），其余 span 原样保留。 */
    public static List<RoadSpan> retypeToReuse(List<RoadSpan> spans, int index,
                                               BlockPos start, BlockPos end) {
        if (spans == null || index < 0 || index >= spans.size()) return spans;
        List<RoadSpan> out = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            RoadSpan span = spans.get(i);
            if (i == index) {
                out.add(new RoadSpan(start != null ? start : span.start(),
                        end != null ? end : span.end(), SpanType.NETWORK_REUSE));
            } else {
                out.add(span);
            }
        }
        return out;
    }
}
