/* 文件职责：钉住端点边界锁定的三条操作 —— 它们是「结构/村庄端点必须落在实测地表」这条硬契约的执行体。 */
package net.shiroha233.roadweaver.pathlogic.core;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点剖面操作测试（2026-11-14）。
 *
 * <h2>为什么现在才补</h2>
 * <p>这三个方法（{@code applySharedApproachProfile} / {@code applySingleBoundaryGrade} /
 * {@code applyPreferCutBoundaryGrade}）是 {@code TargetYComputation} 里端点锁定逻辑的
 * 实际执行体：村庄 / 出生点 / 命名结构 / 未知结构端点都必须落在<b>实测地表</b>，
 * 差一格玩家就能一眼看出来（旧版实测过 {@code targetY[0]=407} 却铺成 389）。</p>
 *
 * <p>它们原本只被一条端到端仿真（{@code EndpointLockSimulationTest}）间接覆盖，
 * 而偏差一旦只发生在「某个方向 / 某个越界情形」上，仿真抓不住。所以这里按
 * <b>每个分支</b>钉：方向、越界截断、只降不升、坡度上限。</p>
 *
 * <h2>三个方法的分工</h2>
 * <ul>
 *   <li><b>shared</b>：端点与别的路共享同一高度序列 → 把共享剖面逐站铺到端点上，
 *       <b>不做坡度修正</b>（共享剖面本身已经是协调过的）。</li>
 *   <li><b>single</b>：已知实测地表 → 钉住端点，再向内侧铺一段 ±1 的引道。</li>
 *   <li><b>prefer-cut</b>：未知结构端点（协商高度）→ 钉住端点，然后<b>只把高的一侧压下来</b>，
 *       低于坡线的站<b>一格都不抬</b>。这是「宁可挖、不肯造天桥」的语义。</li>
 * </ul>
 */
class EndpointProfileOpsTest {

    private static int[] filled(int n, int y) {
        int[] a = new int[n];
        Arrays.fill(a, y);
        return a;
    }

    // ── applySharedApproachProfile ────────────────────────────────────────

    @Test
    void 共享剖面从起点向外逐站复制() {
        int[] profile = filled(8, 64);
        int[] shared = {70, 69, 68, 67};

        EndpointProfileOps.applySharedApproachProfile(profile, 0, shared);

        assertArrayEquals(new int[]{70, 69, 68, 67, 64, 64, 64, 64}, profile,
                "endpoint=0 时方向朝 +x，共享剖面必须逐站照抄，内侧不受影响");
    }

    @Test
    void 共享剖面从终点向内逐站复制() {
        int[] profile = filled(8, 64);
        int[] shared = {70, 69, 68};

        EndpointProfileOps.applySharedApproachProfile(profile, 7, shared);

        assertArrayEquals(new int[]{64, 64, 64, 64, 64, 68, 69, 70}, profile,
                "endpoint=n−1 时方向朝 −x：第 n−1 站拿 shared[0]，向内依次取");
    }

    @Test
    void 共享剖面比路线长时被截断而不是越界() {
        int[] profile = filled(5, 64);
        int[] shared = {70, 69, 68, 67, 66, 65, 64, 63};

        EndpointProfileOps.applySharedApproachProfile(profile, 0, shared);

        assertArrayEquals(new int[]{70, 69, 68, 67, 66}, profile,
                "共享剖面 8 站、路线 5 站 ⇒ 只铺 5 站，剩下的丢弃，不许抛异常");
    }

    @Test
    void 共享剖面的空输入与非端点都是空操作() {
        int[] profile = filled(6, 64);
        int[] snapshot = profile.clone();

        EndpointProfileOps.applySharedApproachProfile(profile, 0, null);
        EndpointProfileOps.applySharedApproachProfile(profile, 0, new int[0]);
        EndpointProfileOps.applySharedApproachProfile(profile, -1, new int[]{70});
        EndpointProfileOps.applySharedApproachProfile(profile, 6, new int[]{70});
        EndpointProfileOps.applySharedApproachProfile(null, 0, new int[]{70});

        assertArrayEquals(snapshot, profile, "null / 空共享剖面 / 端点越界都必须是空操作");
    }

    // ── applySingleBoundaryGrade ─────────────────────────────────────────

    @Test
    void 已知地表端点被精确钉住() {
        int[] profile = filled(21, 60);

        EndpointProfileOps.applySingleBoundaryGrade(profile, 0, 73);

        assertEquals(73, profile[0], "端点必须精确等于实测地表，差一格就是玩家能看出来的错位");
    }

    @Test
    void 已知地表端点的引道每站最多动一格() {
        int[] profile = filled(21, 60);

        EndpointProfileOps.applySingleBoundaryGrade(profile, 0, 73);

        // 引道窗口 = max(1, min(96, (21-1)/2)) = 10 站
        for (int i = 1; i <= 10; i++) {
            int delta = Math.abs(profile[i] - profile[i - 1]);
            assertTrue(delta <= 1,
                    "引道第 " + i + " 站与前站相差 " + delta + " 格，超过 ±1 上限："
                            + Arrays.toString(profile));
        }
        assertTrue(profile[10] < 73, "引道必须向内降下来，不能一路平推 73");
    }

    @Test
    void 单边界操作对越界端点与null是空操作() {
        int[] profile = filled(10, 64);
        int[] snapshot = profile.clone();

        EndpointProfileOps.applySingleBoundaryGrade(profile, -1, 70);
        EndpointProfileOps.applySingleBoundaryGrade(profile, 10, 70);
        EndpointProfileOps.applySingleBoundaryGrade(null, 0, 70);

        assertArrayEquals(snapshot, profile, "越界端点与 null 剖面都必须是空操作");
    }

    // ── applyPreferCutBoundaryGrade（核心契约：只降不升）─────────────────

    @Test
    void 偏好挖方的边界锁定绝不抬高低于坡线的站() {
        // 端点要钉在 100，但邻居都在 5 —— 这是「结构在高处、山谷在下面」的极端情形。
        int[] profile = {5, 5, 5, 5, 5};
        profile[0] = 5;

        EndpointProfileOps.applyPreferCutBoundaryGrade(profile, 0, 5);
        assertArrayEquals(new int[]{5, 5, 5, 5, 5}, profile,
                "端点是 5、邻居也是 5 ⇒ 什么都不该变");

        int[] steep = {0, 0, 0, 0, 0};
        EndpointProfileOps.applyPreferCutBoundaryGrade(steep, 0, 100);
        assertArrayEquals(new int[]{100, 0, 0, 0, 0}, steep,
                "★ 只降不升：邻居低于坡线时必须保持 0，绝不能被动抬高去迁就端点");
    }

    @Test
    void 偏好挖方把高处一站一格压到锁定高度() {
        int[] profile = {0, 100, 100, 100, 100};

        EndpointProfileOps.applyPreferCutBoundaryGrade(profile, 0, 50);

        assertArrayEquals(new int[]{50, 51, 52, 53, 54}, profile,
                "高于坡线（前站+1）的站必须一站一格压下来，最终与锁定高度平滑衔接");
    }

    @Test
    void 偏好挖方从终点方向工作且对越界安全() {
        int[] profile = {100, 100, 100, 0};
        EndpointProfileOps.applyPreferCutBoundaryGrade(profile, 3, 50);
        // 从终点向内：index2 先被压到 50+1=51，index1 到 52，index0 到 53
        assertArrayEquals(new int[]{53, 52, 51, 50}, profile,
                "endpoint=n−1 时方向朝 −x，同样一站一格压下来（越靠内被压得越少）");

        int[] untouched = {1, 1, 1, 1};
        EndpointProfileOps.applyPreferCutBoundaryGrade(untouched, 4, 9);
        assertArrayEquals(new int[]{1, 1, 1, 1}, untouched, "端点越界必须是空操作");
    }
}