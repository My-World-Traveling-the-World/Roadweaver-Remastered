/* 文件职责：验证地图搜索状态机 —— 防抖、序号作废、维度/查询一致性、本地优先合并。 */
package net.shiroha233.roadweaver.client.map;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import net.shiroha233.roadweaver.map.search.MapSearchResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索状态机。
 *
 * <h2>这些边界以前一条都测不到</h2>
 * <p>它原本寄生在 1243 行的 {@code RoadMapScreen} 里。要测「旧响应晚到会不会覆盖新结果」，
 * 得先造一个 Minecraft 客户端并伪造网络包。现在两个外部依赖都是注入的接口，
 * 纯 JVM 就能把五种竞态跑一遍。</p>
 *
 * @see MapSearchController
 */
class MapSearchControllerTest {

    private static final ResourceLocation OVERWORLD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER =
            ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    /** 记录发出的请求。 */
    private static final class FakeSender implements MapSearchController.RequestSender {
        final List<MapSearchController.Request> sent = new ArrayList<>();
        @Override public void send(MapSearchController.Request request) { sent.add(request); }
    }

    /** 可变的本地别名结果。 */
    private static final class FakeLookup implements MapSearchController.LocalAliasLookup {
        final AtomicReference<List<MapSearchResult>> value = new AtomicReference<>(List.of());
        @Override public List<MapSearchResult> find(String query) { return value.get(); }
    }

    private static MapSearchResult result(int x, int z, String id) {
        return new MapSearchResult(new BlockPos(x, 0, z), id, 0);
    }

    private static MapSearchController controller(FakeSender sender, FakeLookup lookup, int max) {
        return new MapSearchController(sender, lookup, max);
    }

    // ── 本地优先与防抖 ───────────────────────────────────────

    @Test
    void 输入变化必须立刻出本地结果并布下防抖() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        lookup.value.set(List.of(result(10, 20, "local")));
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 1_000L);

        assertEquals(1, search.results().size(), "本地别名应立刻可见，不等网络");
        assertTrue(sender.sent.isEmpty(), "case=c1");

        search.tick(1_000L + MapSearchController.DEBOUNCE_MS - 1L, OVERWORLD);
        assertTrue(sender.sent.isEmpty(), "case=c2");

        search.tick(1_000L + MapSearchController.DEBOUNCE_MS, OVERWORLD);
        assertEquals(1, sender.sent.size(), "到期后必须发一次请求");
        assertEquals(1, sender.sent.get(0).seq());
        assertEquals("fort", sender.sent.get(0).query());
        assertEquals(OVERWORLD, sender.sent.get(0).dimensionId());
    }

    @Test
    void 清空输入必须取消待发请求且不产生请求() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 0L);
        search.onQueryChanged("   ", 100L);          // 只留空格等于清空
        search.tick(10_000L, OVERWORLD);

        assertTrue(sender.sent.isEmpty(), "case=c3");
        assertTrue(search.results().isEmpty(), "case=c-empty-after-clear");
        assertFalse(search.loading(), "case=c-not-loading-after-clear");
    }

    @Test
    void 连续输入只应发出最后一次请求() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("f", 0L);
        search.onQueryChanged("fo", 100L);
        search.onQueryChanged("for", 200L);
        search.tick(200L + MapSearchController.DEBOUNCE_MS, OVERWORLD);

        assertEquals(1, sender.sent.size(), "打字过程中不该每敲一下就发请求");
        assertEquals("for", sender.sent.get(0).query(), "发出去的必须是最后那个查询");
    }

    // ── 超时 ─────────────────────────────────────────────────

    @Test
    void 服务端不响应时必须在超时后自己收摊() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 0L);
        search.submitNow(1_000L, OVERWORLD);
        assertTrue(search.loading(), "case=c-loading-after-submit");

        search.tick(1_000L + MapSearchController.TIMEOUT_MS, OVERWORLD);
        assertTrue(search.loading(), "case=c4");

        search.tick(1_000L + MapSearchController.TIMEOUT_MS + 1L, OVERWORLD);
        assertFalse(search.loading(), "case=c5");
        assertTrue(search.failed(), "case=c6");
    }

    @Test
    void 超时后晚到的响应必须被丢弃() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchController search = controller(sender, lookup, 8);

        // 时间基准不能用 0：超时判定带 startedAtMs > 0 的防御条件
        // （原代码就有，现实里 currentTimeMillis 不会为 0），
        // 在 0 时刻提交的请求永远不会被判超时。
        long t0 = 5_000L;
        search.onQueryChanged("fort", t0);
        search.submitNow(t0, OVERWORLD);
        int staleSeq = sender.sent.get(0).seq();

        search.tick(t0 + MapSearchController.TIMEOUT_MS + 1L, OVERWORLD);  // 触发超时，序号自增
        assertTrue(search.failed(), "case=c-timeout-flagged");

        search.acceptResults(staleSeq, OVERWORLD, OVERWORLD, true,
                List.of(result(1, 1, "server")));

        assertTrue(search.results().isEmpty(), "case=c-stale-dropped");
    }

    // ── 三重过滤 ─────────────────────────────────────────────

    @Test
    void 序号不匹配的响应必须忽略() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 0L);
        search.submitNow(0L, OVERWORLD);
        int seq = sender.sent.get(0).seq();

        search.acceptResults(seq + 99, OVERWORLD, OVERWORLD, true, List.of(result(1, 1, "s")));
        assertTrue(search.results().isEmpty(), "case=c7");
    }

    @Test
    void 维度不匹配的响应必须忽略() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 0L);
        search.submitNow(0L, OVERWORLD);
        int seq = sender.sent.get(0).seq();

        search.acceptResults(seq, NETHER, OVERWORLD, true, List.of(result(1, 1, "s")));
        assertTrue(search.results().isEmpty(), "case=c8");
    }

    @Test
    void 用户已改字时即使序号对得上也要丢弃结果() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 0L);
        search.submitNow(0L, OVERWORLD);
        int seq = sender.sent.get(0).seq();

        search.onQueryChanged("fortress", 10L);      // 用户又改了字
        search.acceptResults(seq, OVERWORLD, OVERWORLD, true, List.of(result(1, 1, "s")));

        assertTrue(search.results().isEmpty(), "case=c-query-changed-dropped");
    }

    // ── 合并 ─────────────────────────────────────────────────

    @Test
    void 服务端结果必须与本地合并去重且同坐标由服务端覆盖() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchResult local = result(10, 20, "local");
        lookup.value.set(List.of(local));
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 0L);
        search.submitNow(0L, OVERWORLD);
        int seq = sender.sent.get(0).seq();

        search.acceptResults(seq, OVERWORLD, OVERWORLD, true, List.of(
                result(10, 20, "server-same"),        // 与本地同坐标
                result(30, 40, "server-new")));

        assertEquals(2, search.results().size(), "case=dedup-by-pos");
        // ★ 既存行为（本次重构保持原样）：合并时 containsKey 只用来控制「到上限就停」，
        //   并不阻止 put ⇒ 同坐标时**服务端值覆盖本地值**，但 LinkedHashMap 保留
        //   本地条目原来的位置。所以顺序不变、内容变成服务端那条。
        assertEquals("server-same", search.results().get(0).structureId(), "case=same-pos-server-wins");
        assertEquals("server-new", search.results().get(1).structureId(), "case=new-pos-appended");
    }

    @Test
    void 合并结果必须遵守数量上限() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        lookup.value.set(List.of(result(1, 1, "l1")));
        MapSearchController search = controller(sender, lookup, 3);

        search.onQueryChanged("fort", 0L);
        search.submitNow(0L, OVERWORLD);
        int seq = sender.sent.get(0).seq();

        search.acceptResults(seq, OVERWORLD, OVERWORLD, true, List.of(
                result(2, 2, "s2"), result(3, 3, "s3"),
                result(4, 4, "s4"), result(5, 5, "s5")));

        assertEquals(3, search.results().size(), "上限 3：1 条本地 + 2 条服务端");
    }

    @Test
    void 请求失败时必须标记失败且不改动已有结果() {
        FakeSender sender = new FakeSender();
        FakeLookup lookup = new FakeLookup();
        MapSearchResult local = result(7, 7, "local");
        lookup.value.set(List.of(local));
        MapSearchController search = controller(sender, lookup, 8);

        search.onQueryChanged("fort", 0L);
        search.submitNow(0L, OVERWORLD);
        int seq = sender.sent.get(0).seq();
        List<MapSearchResult> beforeResults = search.results();

        search.acceptResults(seq, OVERWORLD, OVERWORLD, false, List.of(result(1, 1, "s")));

        assertTrue(search.failed(), "case=c9");
        assertFalse(search.loading(), "case=c-not-loading");
        assertEquals(beforeResults, search.results(), "失败不该清掉已经在显示的结果");
    }

    // ── 结果键 ───────────────────────────────────────────────

    @Test
    void 结果键必须能区分正负坐标() {
        long a = MapSearchController.resultKey(new BlockPos(1, 0, 2));
        long b = MapSearchController.resultKey(new BlockPos(-1, 0, 2));
        long c = MapSearchController.resultKey(new BlockPos(1, 0, -2));
        long d = MapSearchController.resultKey(new BlockPos(1, 0, 2));

        assertNotEquals(a, b, "x 取负必须换键");
        assertNotEquals(a, c, "z 取负必须换键");
        assertEquals(a, d, "同坐标必须同键");
        assertNotEquals(MapSearchController.resultKey(new BlockPos(0, 0, 0)),
                MapSearchController.resultKey(new BlockPos(-1, 0, -1)),
                "极端负数也不能撞键");
    }
}
