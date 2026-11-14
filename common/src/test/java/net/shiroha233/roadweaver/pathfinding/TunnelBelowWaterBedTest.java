/* 文件职责：钉住「隧道能否从河床下方穿过」的判据边界 —— 哨兵、夹取、溢出、严格小于。 */
package net.shiroha233.roadweaver.pathfinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隧道从河床下穿过判据测试（2026-11-15）。
 *
 * <h2>被测的是什么</h2>
 * <p>{@code SpanConflictResolver.tunnelFitsBelowWaterBed(roadY, clearHeight, bedY)}
 * 回答一个问题：<b>一段隧道从河床下面走，会不会顶穿河床？</b>
 * 判据是「隧道净空顶面（{@code roadY + clearHeight - 1}）必须严格低于河床底面」。</p>
 *
 * <p>它决定的是「水面上的桥」还是「水下的隧道」—— 判错方向会直接毁掉地形：
 * 乐观判错 ⇒ 隧道顶穿河床、水灌进隧道；保守判错 ⇒ 明明能走隧道却去架桥。</p>
 *
 * <h2>四个必须钉住的边界</h2>
 * <ol>
 *   <li><b>哨兵值</b>：{@code bedY == Integer.MIN_VALUE} 表示「这里没有河床数据」，
 *       必须返回 {@code false}（不能拿 MIN_VALUE 去比 —— 它是「未知」，不是「很低」）；</li>
 *   <li><b>净空夹取</b>：{@code clearHeight} 被夹到 <b>[2, 16]</b>。
 *       下界 2 防「零净空」，上界 16 防配置写大了把隧道判成不可能；</li>
 *   <li><b>溢出保护</b>：{@code roadY} 接近 {@code Integer.MAX_VALUE} 时，
 *       {@code roadY + clearHeight - 1} 用 int 会溢出成负数 ⇒ 判据反转。
 *       实现里用 {@code long} 承接，这个测试证明它不是多余的；</li>
 *   <li><b>严格小于</b>：顶面<b>等于</b>河床底面时<b>不</b>合格（隧道顶和河床底同高 ⇒ 顶穿）。</li>
 * </ol>
 */
class TunnelBelowWaterBedTest {

    @Test
    void 净空顶面低于河床时成立() {
        // 路面 y=50，净空 4 ⇒ 顶面 = 50+4-1 = 53；河床底 60
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 4, 60),
                "顶面 53 < 河床 60 ⇒ 能穿过");
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 4, 54),
                "顶面 53 < 河床 54，差 1 格也算能穿过");
    }

    @Test
    void 顶面等于河床时不成立() {
        // ★ 严格小于：顶面必须 < 河床底
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 4, 53),
                "顶面 53 == 河床 53 ⇒ 隧道顶与河床底同高，会顶穿，必须判不成立");
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 4, 52),
                "顶面 53 > 河床 52 ⇒ 明确顶穿");
    }

    @Test
    void 河床哨兵值必须判不成立() {
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 4, Integer.MIN_VALUE),
                "MIN_VALUE 是「没有河床数据」的哨兵，不是「河床极低」。"
                        + "若拿它参与比较，任何隧道都会被判成「能从底下穿过」");
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(Integer.MAX_VALUE, 16, Integer.MIN_VALUE),
                "哨兵判断必须在计算之前，与 roadY 无关");
    }

    @Test
    void 净空下界被夹到2() {
        // clearHeight=0 与 1 都应等价于 2 ⇒ 顶面 = 50+2-1 = 51
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 0, 51),
                "clearHeight=0 被夹成 2 ⇒ 顶面 51，与河床 51 同高 ⇒ 不成立");
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 0, 52),
                "clearHeight=0 夹成 2 ⇒ 顶面 51 < 52 ⇒ 成立");
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 1, 51),
                "clearHeight=1 同样夹成 2");
    }

    @Test
    void 净空上界被夹到16() {
        // clearHeight=100 应等价于 16 ⇒ 顶面 = 50+16-1 = 65
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 100, 65),
                "clearHeight 被夹到 16 ⇒ 顶面 65，与河床 65 同高 ⇒ 不成立");
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 100, 66),
                "clearHeight 夹到 16 ⇒ 顶面 65 < 66 ⇒ 成立");
        // ★ 这条才是「夹取被删」的判别式：
        //   夹取后顶面 65 < 河床 100 ⇒ true
        //   若不夹取，顶面 149 < 河床 100 ⇒ false
        //   所以断言 true 就同时证明了上界夹取生效。
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(50, 100, 100),
                "clearHeight=100 必须被夹到 16 ⇒ 顶面 65 < 河床 100 ⇒ 判成立。"
                        + "若有人删掉夹取，顶面会变成 149，同一条断言就会红 —— "
                        + "这是「上界夹取被删」的判别式");
    }

    @Test
    void 接近Integer上界时不得溢出() {
        // ★ 这条是 long 承接的必要性证明。
        //   roadY = MAX-2、clearHeight = 16 ⇒ 顶面 = MAX+13（数学值）
        //   用 int 算会溢出成负数 ⇒ 「负数 < MAX」⇒ 误判为「能穿过」
        //   用 long 算 ⇒ MAX+13 < MAX 为假 ⇒ 正确判「不能穿过」
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(
                        Integer.MAX_VALUE - 2, 16, Integer.MAX_VALUE),
                "顶面数学上是 MAX+13，远高于河床 MAX ⇒ 必须判不成立。"
                        + "若实现用 int 相加，这里会溢出成负数，于是误判成「能穿过」");

        // 反面：确实能穿过时（河床更高是不可能的，所以只能验证不崩溃、结果稳定）
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(
                        Integer.MAX_VALUE - 2, 2, Integer.MAX_VALUE),
                "clearHeight 夹到 2 ⇒ 顶面 = MAX-1 < MAX ⇒ 成立。"
                        + "这条同时证明没有因为上一条而把整个分支写死成 false");
    }

    @Test
    void 负坐标与深地也正确() {
        // 世界的 Y 可以是负数（深板岩层），河床也可能在 y < 0
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(-50, 4, -40),
                "顶面 -47 < 河床 -40 ⇒ 成立");
        assertFalse(SpanConflictResolver.tunnelFitsBelowWaterBed(-50, 4, -47),
                "顶面 -47 == 河床 -47 ⇒ 不成立（严格小于）");

        // 极低处：roadY = MIN_VALUE 时 clearHeight 夹取后仍会溢出吗？
        // 顶面 = MIN_VALUE + 2 - 1 = MIN_VALUE+1（long 里正常）
        assertTrue(SpanConflictResolver.tunnelFitsBelowWaterBed(
                        Integer.MIN_VALUE, 2, Integer.MIN_VALUE + 2),
                "顶面 MIN+1 < 河床 MIN+2 ⇒ 成立。用 int 相加在这里是安全的（往负方向加正数），"
                        + "但用 long 也无害 —— 这条确认实现没有在负方向过度限制");
    }
}