package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 吸附计算的两条硬闸（0.9.029 / R-03）：**过渡不超过每站 1 格**，**过渡不下就整体放弃**。
 *
 * <p>用户 2026-11-08 明确要求「不会导致道路失败吧只是合并吸附对吧」——
 * 这里的两组用例就是那句话的机械证明：改得动才改，改不动一律返回 null 保持原样。
 */
class BridgeShareAbsorberTest {

    private static List<Integer> flat(int n, int y) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(y);
        return out;
    }

    /** 主人在 deckY=65，次路段在 y=63，两侧各有足够站 ⇒ 吸附成功且过渡平滑。 */
    @Test
    void absorbsWhenTransitionFits() {
        List<Integer> targetY = flat(21, 63);
        int[] out = BridgeShareAbsorber.blendToDeckY(targetY, 8, 12, 65);

        assertNotNull(out, "两侧各 8 站，足够把 2 格差过渡回去，必须吸附");
        for (int i = 8; i <= 12; i++) {
            assertEquals(65, out[i], "被吸收区间内必须等于主人 deckY，站 " + i);
        }
        for (int i = 1; i < out.length; i++) {
            assertTrue(Math.abs(out[i] - out[i - 1]) <= BridgeShareAbsorber.MAX_TRANSITION_PER_STATION,
                    "相邻站高差必须 ≤1，第 " + i + " 站=" + out[i] + " 前一站=" + out[i - 1]);
        }
    }

    /** 差 10 格但两侧只剩 2 站 ⇒ 过渡不下，必须整体放弃（返回 null），绝不能半改。 */
    @Test
    void abortsWhenTransitionDoesNotFit() {
        List<Integer> targetY = flat(11, 55);
        int[] out = BridgeShareAbsorber.blendToDeckY(targetY, 4, 6, 65);

        assertNull(out, "差 10 格却只有 4 站可用，必须放弃吸附而不是造断崖");
    }

    /** 已经在同一高度：不用过渡，直接吸附且剖面不变形。 */
    @Test
    void alreadyLevelNeedsNoTransition() {
        List<Integer> targetY = flat(21, 65);
        int[] out = BridgeShareAbsorber.blendToDeckY(targetY, 8, 12, 65);

        assertNotNull(out);
        for (int i = 0; i < out.length; i++) {
            assertEquals(65, out[i], "本来就在 deckY，整条剖面应逐格不变");
        }
    }

    /** span 改类型只动目标那一个，其余 span 原样保留。 */
    @Test
    void retypesOnlyTheTargetSpan() {
        BlockPos a = new BlockPos(0, 63, 0);
        BlockPos b = new BlockPos(10, 63, 0);
        BlockPos c = new BlockPos(20, 63, 0);
        List<RoadSpan> spans = List.of(
                new RoadSpan(a, b, SpanType.BRIDGE),
                new RoadSpan(b, c, SpanType.TUNNEL));

        List<RoadSpan> out = BridgeShareAbsorber.retypeToReuse(spans, 0, a, b);

        assertEquals(2, out.size());
        assertEquals(SpanType.NETWORK_REUSE, out.get(0).type(), "目标 span 必须变成 NETWORK_REUSE");
        assertEquals(SpanType.TUNNEL, out.get(1).type(), "其它 span 的类型不许动");
        assertSame(spans.get(1), out.get(1), "其它 span 对象应原样保留");
    }

    /** 越界/空输入不许抛。 */
    @Test
    void degenerateInputsAreSafe() {
        assertNull(BridgeShareAbsorber.blendToDeckY(null, 0, 1, 64));
        assertNull(BridgeShareAbsorber.blendToDeckY(List.of(), 0, 1, 64));
        List<RoadSpan> spans = List.of(new RoadSpan(new BlockPos(0, 63, 0), new BlockPos(1, 63, 0), SpanType.BRIDGE));
        assertSame(spans, BridgeShareAbsorber.retypeToReuse(spans, 5, null, null), "越界索引应原样返回");
    }
}
