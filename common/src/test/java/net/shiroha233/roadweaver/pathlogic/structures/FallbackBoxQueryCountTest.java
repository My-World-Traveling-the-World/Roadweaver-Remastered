/* 文件职责：钉住「occupancy 缺失因而放弃精确判定」这件事必须可计数。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fallback 盒查询的可观测性测试（2026-11-15）。
 *
 * <h2>它守的是「别让第二种 null 变成无声」</h2>
 * <p>{@code OwnedStructureBox.intersects} 在 {@code occupancy == null} 时直接返回 false，
 * 而 null 的两种原因<b>完全同形</b>：</p>
 * <ul>
 *   <li>① 这个结构本来就只有预测盒、没有模板方块数据 ⇒ <b>正常</b>，规划刻意忽略它；</li>
 *   <li>② 模板解析失败 ⇒ <b>异常</b>，这条路对该结构完全失明，可能直接穿过建筑。</li>
 * </ul>
 *
 * <p>本测试不改变返回值（仍然 false —— 改成 true 会把每个只有预测盒的结构都当成硬墙，
 * 那会把路网逼得无路可走），只钉住「发生了几次」这件事能被取到。</p>
 *
 * <h2>为什么增量必须精确到每次调用</h2>
 * <p>如果只在首次计数，真机上就分不清「5 次」和「5 万次」。
 * 这个数字要能回答「是不是大量结构都只剩 fallback 盒」，所以每次都要加。</p>
 */
class FallbackBoxQueryCountTest {

    private static final StructureOwnerKey OWNER = new StructureOwnerKey(0L, "minecraft:village_plains");

    /** 一个 occupancy 为 null（只有 fallback 盒）的结构盒。 */
    private static OwnedStructureBox fallbackOnly() {
        return new OwnedStructureBox(OWNER, new BoundingBox(0, 60, 0, 16, 80, 16));
    }

    @Test
    void occupancy缺失时判定返回false且计数增加() {
        StructureAvoidanceDiagnostics.resetFallbackBoxOnlyQueries();
        assertEquals(0, StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries(), "重置后应为 0");

        OwnedStructureBox box = fallbackOnly();
        // 盒内位置 —— 只有 occupancy 缺失才会走到计数分支
        boolean hit = box.intersects(8, 8, 60, 80, 0);

        assertFalse(hit,
                "occupancy 缺失时必须返回 false —— 返回值语义不能变，"
                        + "改成 true 会让每个只有预测盒的结构变成硬墙");
        assertEquals(1, StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries(),
                "失明查询必须被计数 —— 这就是「可见」的来源");
    }

    @Test
    void 每次调用都累加而不是只在首次() {
        StructureAvoidanceDiagnostics.resetFallbackBoxOnlyQueries();
        OwnedStructureBox box = fallbackOnly();

        for (int i = 0; i < 5; i++) {
            box.intersects(8, 8, 60, 80, 0);
        }

        assertEquals(5, StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries(),
                "必须每次累加 —— 只计首次就分不清「5 次」和「5 万次」，"
                        + "而这个数字正是用来判断「是不是大量结构都只剩 fallback 盒」的");
    }

    @Test
    void intersectsSolid同样计数() {
        StructureAvoidanceDiagnostics.resetFallbackBoxOnlyQueries();
        OwnedStructureBox box = fallbackOnly();

        boolean hit = box.intersectsSolid(8, 8, 60, 80);

        assertFalse(hit, "occupancy 缺失时 intersectsSolid 也必须返回 false");
        assertEquals(1, StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries(),
                "两个入口都要计数 —— 只覆盖一个会让另一条调用路径继续隐形");
    }

    @Test
    void 盒子范围外不计数() {
        StructureAvoidanceDiagnostics.resetFallbackBoxOnlyQueries();
        OwnedStructureBox box = fallbackOnly();

        // 远在盒外：在 occupancy 判定之前就被几何粗筛挡住
        boolean hit = box.intersects(1000, 1000, 60, 80, 0);

        assertFalse(hit);
        assertEquals(0, StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries(),
                "盒外查询不该计入 —— 那不是「失明」，是「本来就不在范围内」。"
                        + "把两者混在一起会让这个数字虚高、失去诊断价值");
    }

    @Test
    void Y范围不重叠也不计数() {
        StructureAvoidanceDiagnostics.resetFallbackBoxOnlyQueries();
        OwnedStructureBox box = fallbackOnly();

        // XZ 在盒内但 Y 完全不重叠（盒在 60..80）
        boolean hit = box.intersects(8, 8, 100, 120, 0);

        assertFalse(hit);
        assertEquals(0, StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries(),
                "Y 不重叠属于几何判否，不是失明");
    }

    @Test
    void 重置能清零() {
        StructureAvoidanceDiagnostics.resetFallbackBoxOnlyQueries();
        fallbackOnly().intersects(8, 8, 60, 80, 0);
        assertTrue(StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries() > 0);

        StructureAvoidanceDiagnostics.resetFallbackBoxOnlyQueries();
        assertEquals(0, StructureAvoidanceDiagnostics.fallbackBoxOnlyQueries(),
                "重置必须归零 —— 每次规划会话需要干净的起点");
    }
}