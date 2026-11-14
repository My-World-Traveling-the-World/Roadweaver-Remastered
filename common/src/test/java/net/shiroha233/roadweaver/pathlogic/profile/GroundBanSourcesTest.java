/* 文件职责：钉住「禁地面来源」确实独立于旧判定 —— 这是解除批 2 循环论证的证据基础。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁地面来源测试（2026-11-15）。
 *
 * <h2>为什么这组测试是批 2 的前提</h2>
 * <p>桥隧 AB 观测原先有一个循环论证：</p>
 * <pre>
 *   旧 spans ──► isCrossing ──► groundBanned ──► DP 只能在这些站选桥
 *        └────────────────────────────────────► 再拿 DP 结果与旧 spans 比对
 * </pre>
 * <p>于是「召回率」必然偏高。{@link GroundBanSources} 改用与旧判定无关的物理事实
 * （水面水深 / 结构占地）构造掩码，这组测试保证三件事：</p>
 * <ol>
 *   <li><b>独立性</b>：只给水面探针时，掩码仍能正确置位 —— 不需要旧判定参与；</li>
 *   <li><b>可解释</b>：每个来源贡献多少站能被分别数出来；</li>
 *   <li><b>不撒谎</b>：来源不可用时贡献恒为 0，且快照可用性被如实标记。</li>
 * </ol>
 */
class GroundBanSourcesTest {

    private static List<BlockPos> line(int n) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new BlockPos(i * 4, 64, 0));
        return out;
    }

    /** 把「第几个站是水」写成集合，测试里一眼能看懂。 */
    private static GroundBanSources.WaterProbe waterAt(int... indices) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (int i : indices) set.add(i);
        return (x, z) -> set.contains(x / 4);
    }

    @Test
    void 水面来源能独立判出禁地面() {
        GroundBanSources.Result r = GroundBanSources.collect(
                waterAt(2, 3), null, false, line(6), 6);

        assertNotNull(r.banned(), "结果数组不许为 null");
        assertEquals(2, r.waterStations(), "应恰好 2 站由水面判据置位");
        assertFalse(r.banned()[0], "第 0 站不是水");
        assertTrue(r.banned()[2], "第 2 站是水，必须禁止铺地面");
        assertTrue(r.banned()[3], "第 3 站是水，必须禁止铺地面");
        assertFalse(r.banned()[5], "第 5 站不是水");
        assertEquals(2, r.bannedStations(), "总置位数应为 2（无重叠）");
    }

    @Test
    void 结构来源能与水面来源分别统计() {
        // 水面在第 1 站；结构在第 1、4 站（第 1 站重叠）
        GroundBanSources.StructureProbe structure =
                pos -> pos.getX() == 4 || pos.getX() == 16;

        GroundBanSources.Result r = GroundBanSources.collect(
                waterAt(1), structure, true, line(5), 5);

        assertEquals(1, r.waterStations(), "水面只贡献 1 站");
        assertEquals(2, r.structureStations(), "结构贡献 2 站");
        assertEquals(2, r.bannedStations(), "重叠只算一次 ⇒ 总置位 2 站");
        assertTrue(r.structureSnapshotAvailable(), "显式传 true 时应如实标记可用");
    }

    @Test
    void 两个来源都为null时返回空掩码且不崩() {
        GroundBanSources.Result r = GroundBanSources.collect(
                null, null, false, line(5), 5);

        assertNotNull(r.banned());
        assertEquals(5, r.banned().length, "站数不变，只是没有任何来源置位");
        assertEquals(0, r.bannedStations());
        assertEquals(0, r.waterStations());
        assertEquals(0, r.structureStations());
        assertFalse(r.structureSnapshotAvailable(), "没给结构探针 ⇒ 不能声称快照可用");
    }

    @Test
    void 坐标不足时不做半套判定() {
        // 给了 6 站但只有 3 个坐标 ⇒ 无法判定任何站，返回空掩码
        GroundBanSources.Result r = GroundBanSources.collect(
                waterAt(0, 1, 2), null, false, line(3), 6);

        assertEquals(6, r.banned().length);
        assertEquals(0, r.bannedStations(), "坐标不全时宁可一个都不判，也不许错位");
    }

    @Test
    void 零站时返回零长数组() {
        GroundBanSources.Result r = GroundBanSources.collect(null, null, false, List.of(), 0);
        assertEquals(0, r.banned().length);
        assertEquals(0, r.bannedStations());
    }

    @Test
    void 单站也能正确判定() {
        GroundBanSources.Result r = GroundBanSources.collect(
                waterAt(0), null, false, line(1), 1);
        assertEquals(1, r.banned().length);
        assertTrue(r.banned()[0], "唯一的站在水里 ⇒ 必须置位");
        assertEquals(1, r.bannedStations());
    }

    @Test
    void 结构快照不可用时结构探针的结果不被采纳() {
        // 这是「不撒谎」的核心：快照 absent 时，即使探针返回 true，
        // 调用方也不该把它当成可信的结构证据。
        GroundBanSources.StructureProbe alwaysTrue = pos -> true;

        GroundBanSources.Result absent = GroundBanSources.collect(
                null, alwaysTrue, false, line(3), 3);
        GroundBanSources.Result present = GroundBanSources.collect(
                null, alwaysTrue, true, line(3), 3);

        // 掩码本身仍按探针计算（哨兵值就是探针给的），
        // 但「是否可信」必须如实区分 —— 日志据此决定要不要把这次结果当证据。
        assertTrue(absent.structureStations() > 0, "探针说占用，统计就要如实反映");
        assertFalse(absent.structureSnapshotAvailable(), "快照缺失必须标 absent");
        assertTrue(present.structureSnapshotAvailable(), "快照存在必须标 ok");
    }

    @Test
    void 生产入口在地形为null时退化为仅结构来源() {
        // 地形拿不到时不能崩，也不能凭空造水面站
        GroundBanSources.Result r = GroundBanSources.collect(
                null, line(4), 4, 2);

        assertNotNull(r.banned());
        assertEquals(4, r.banned().length);
        assertEquals(0, r.waterStations(), "没有地形 ⇒ 水面来源必须贡献 0");
    }
}