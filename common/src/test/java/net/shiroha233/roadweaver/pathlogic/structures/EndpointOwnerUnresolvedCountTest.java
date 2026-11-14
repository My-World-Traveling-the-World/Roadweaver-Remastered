/* 文件职责：钉住「端点拿不到任何 owner」这个唯一的零 owner 出口必须可计数。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点归属解析失败的可观测性测试（2026-11-15）。
 *
 * <h2>它守的是一个非常窄但很关键的出口</h2>
 * <p>{@code resolveEndpointOwner} 在<b>找到精确 identity（XZ 匹配）时会无条件加一个 owner 键</b>，
 * 所以那条路径不可能「零 owner」。真正的零 owner 出口只有一个：</p>
 * <pre>
 *   identity == null（XZ 没匹配上） 且 inferNearbyEndpointOwner 也返回 null
 * </pre>
 *
 * <p>后果是：这个端点所在的结构<b>不再享受豁免</b>，路可能被自己的目的地建筑挡回来。
 * 而它与「这个端点本来就在结构外面」完全同形，原来日志里一个字都没有。</p>
 *
 * <h2>为什么这个测试必须先验证「哪条路才算失败」</h2>
 * <p>我第一版把这个计数加在方法<b>末尾</b>，以为「四段都失败」会在那里收尾 ——
 * 实际上 identity 找到时已经无条件 {@code result.add(expected)} 了，
 * 方法末尾那条路径根本不可达。是下面的 {@code identity 找到时不计数} 这个测试
 * 把这件事钉住，防止以后又把计数搬到错的地方。</p>
 */
class EndpointOwnerUnresolvedCountTest {

    private static final BlockPos FROM = new BlockPos(0, 64, 0);
    private static final BlockPos TO = new BlockPos(200, 64, 0);

    @Test
    void 端点拿不到owner时计数增加() {
        EndpointOwnerResolver.resetUnresolvedEndpointOwnerCount();

        // infos 里没有任何一条的 XZ 与端点重合 ⇒ identity == null；
        // boxes 为空 ⇒ 附近推断必然失败 ⇒ 两个端点各走一次零 owner 出口。
        List<StructureInfo> infos = List.of(
                new StructureInfo(new BlockPos(9999, 64, 9999), "minecraft:village_plains"));

        Set<StructureOwnerKey> owners = EndpointOwnerResolver.resolveEndpointOwnersForTest(
                new StructureConnection(FROM, TO), infos, Set.of());

        assertTrue(owners.isEmpty(), "两个端点都没解析出 owner，结果必须为空集");
        assertEquals(2, EndpointOwnerResolver.unresolvedEndpointOwnerCount(),
                "路有两端，每端走一次零 owner 出口 ⇒ 应计 2 次。"
                        + "实际 " + EndpointOwnerResolver.unresolvedEndpointOwnerCount());
    }

    @Test
    void identity找到时不计数() {
        EndpointOwnerResolver.resetUnresolvedEndpointOwnerCount();

        // from 的 XZ 与这条 info 完全重合 ⇒ identity 命中 ⇒ 无条件加 owner 键，不算失败
        List<StructureInfo> infos = List.of(new StructureInfo(FROM, "minecraft:village_plains"));

        Set<StructureOwnerKey> owners = EndpointOwnerResolver.resolveEndpointOwnersForTest(
                new StructureConnection(FROM, TO), infos, Set.of());

        assertFalse(owners.isEmpty(),
                "identity 命中时无条件加 owner 键 —— 这条路径不该是空的");
        // TO 端仍然没有 identity，所以是 1 而不是 0
        assertEquals(1, EndpointOwnerResolver.unresolvedEndpointOwnerCount(),
                "只有 TO 端该计失败；FROM 端 identity 命中就该是 0。"
                        + "若这里是 2，说明计数被搬到了方法末尾那种不可达/全量的位置");
    }

    @Test
    void 两端都命中时计数为零() {
        EndpointOwnerResolver.resetUnresolvedEndpointOwnerCount();

        List<StructureInfo> infos = List.of(
                new StructureInfo(FROM, "minecraft:village_plains"),
                new StructureInfo(TO, "minecraft:village_taiga"));

        Set<StructureOwnerKey> owners = EndpointOwnerResolver.resolveEndpointOwnersForTest(
                new StructureConnection(FROM, TO), infos, Set.of());

        assertEquals(2, owners.size(), "两端各命中一条应得到两个 owner 键");
        assertEquals(0, EndpointOwnerResolver.unresolvedEndpointOwnerCount(),
                "两端都命中时不该有任何失败计数");
    }

    @Test
    void 空连接不计数() {
        EndpointOwnerResolver.resetUnresolvedEndpointOwnerCount();

        // connection == null 直接返回，不进入端点解析 —— 这不是「失败」，是「没问」
        assertTrue(EndpointOwnerResolver.resolveEndpointOwnersForTest(null, List.of(), Set.of()).isEmpty());
        assertEquals(0, EndpointOwnerResolver.unresolvedEndpointOwnerCount(),
                "null 连接属于「没问」，不该计入「问了但没答案」");
    }

    @Test
    void 重置能清零() {
        EndpointOwnerResolver.resetUnresolvedEndpointOwnerCount();
        EndpointOwnerResolver.resolveEndpointOwnersForTest(
                new StructureConnection(FROM, TO), List.of(), Set.of());
        assertTrue(EndpointOwnerResolver.unresolvedEndpointOwnerCount() > 0);

        EndpointOwnerResolver.resetUnresolvedEndpointOwnerCount();
        assertEquals(0, EndpointOwnerResolver.unresolvedEndpointOwnerCount(),
                "重置必须归零 —— 每次规划会话需要干净的起点");
    }
}