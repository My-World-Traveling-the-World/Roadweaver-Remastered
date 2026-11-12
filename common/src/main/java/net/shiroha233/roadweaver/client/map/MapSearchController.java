/* 文件职责：地图搜索的纯状态机 —— 防抖、请求序号、超时、本地与服务端结果合并。 */
package net.shiroha233.roadweaver.client.map;

import java.util.LinkedHashMap;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import net.shiroha233.roadweaver.map.search.MapSearchResult;

/**
 * 地图搜索状态机。
 *
 * <h2>为什么把它从界面里拆出来</h2>
 * <p>这段逻辑原本长在 {@code RoadMapScreen}（1243 行）里，是九个字段加四个方法：
 * 查询串、待提交串、已提交串、结果、加载中、失败、请求序号、防抖截止、开始时刻。
 * 它是<b>纯状态机</b>，却因为寄生在 {@code Screen} 里而完全无法测试。</p>
 *
 * <p>而它恰恰藏着一串容易错的地方：</p>
 * <ul>
 *   <li><b>防抖</b>：每敲一个字都发请求会把服务端打爆，所以要等 250ms 静默；</li>
 *   <li><b>请求序号</b>：旧请求的结果必须丢掉，否则打字快时结果会来回跳；</li>
 *   <li><b>查询一致性</b>：即便序号对得上，如果用户已经把输入框改了，结果也不能用；</li>
 *   <li><b>8 秒超时</b>：服务端不响应时必须自己收摊，否则界面永远转圈；</li>
 *   <li><b>本地优先合并</b>：本地备注里的别名能立刻命中，不该等网络。</li>
 * </ul>
 *
 * <h2>两个注入点</h2>
 * <p>发送请求与本地别名查找都通过接口注入，所以本类<b>不依赖网络、不依赖客户端状态</b>，
 * 可以在纯 JVM 里完整测试。调用方自己决定用 {@code ClientNetBridge} 还是假实现。</p>
 *
 * @see net.shiroha233.roadweaver.client.map.RoadMapScreen
 */
public final class MapSearchController {

    /** 输入静默多久才真正发请求。 */
    public static final long DEBOUNCE_MS = 250L;

    /** 请求发出后多久没回音就当失败。 */
    public static final long TIMEOUT_MS = 8_000L;

    /** 待发送的请求。 */
    public record Request(int seq, ResourceLocation dimensionId, String query) {}

    /** 发送请求的通道。 */
    @FunctionalInterface
    public interface RequestSender {
        void send(Request request);
    }

    /** 本地别名查找（不经过网络）。 */
    @FunctionalInterface
    public interface LocalAliasLookup {
        List<MapSearchResult> find(String query);
    }

    private final RequestSender sender;
    private final LocalAliasLookup localLookup;
    private final int maxResults;

    private String query = "";
    private String pendingQuery = "";
    private String submittedQuery = "";
    private List<MapSearchResult> results = List.of();
    private boolean loading;
    private boolean failed;
    private int requestSeq;
    private long deadlineMs;
    private long startedAtMs;

    public MapSearchController(RequestSender sender, LocalAliasLookup localLookup, int maxResults) {
        if (sender == null) throw new IllegalArgumentException("sender 不能为 null");
        if (localLookup == null) throw new IllegalArgumentException("localLookup 不能为 null");
        this.sender = sender;
        this.localLookup = localLookup;
        this.maxResults = Math.max(1, maxResults);
    }

    public String query() { return query; }

    public List<MapSearchResult> results() { return results; }

    public boolean loading() { return loading; }

    public boolean failed() { return failed; }

    /**
     * 输入框内容变了。
     *
     * <p>立刻做一次本地别名查找（不等网络），并重新开始防抖计时。
     * 清空输入时取消一切待发请求 —— 否则清空后还会收到一个空查询的响应。</p>
     */
    public void onQueryChanged(String value, long nowMillis) {
        query = value == null ? "" : value;
        pendingQuery = query.trim();
        failed = false;
        loading = false;
        startedAtMs = 0L;
        results = localLookup.find(pendingQuery);
        if (pendingQuery.isEmpty()) {
            loading = false;
            deadlineMs = 0L;
            return;
        }
        deadlineMs = nowMillis + DEBOUNCE_MS;
    }

    /**
     * 每帧调用一次：处理超时与防抖到期。
     *
     * <p>超时要<b>先</b>处理：一个已经超时的请求不该再被防抖逻辑重复提交。</p>
     */
    public void tick(long nowMillis, ResourceLocation currentDimensionId) {
        if (loading && startedAtMs > 0L && nowMillis - startedAtMs > TIMEOUT_MS) {
            loading = false;
            failed = true;
            startedAtMs = 0L;
            requestSeq++;          // 作废在途响应
        }
        if (deadlineMs <= 0L || nowMillis < deadlineMs) return;
        submitNow(nowMillis, currentDimensionId);
    }

    /** 立即提交当前查询（回车或防抖到期）。 */
    public void submitNow(long nowMillis, ResourceLocation currentDimensionId) {
        String trimmed = query.trim();
        deadlineMs = 0L;
        if (trimmed.isEmpty() || currentDimensionId == null) {
            loading = false;
            results = List.of();
            return;
        }
        submittedQuery = trimmed;
        loading = true;
        failed = false;
        startedAtMs = nowMillis;
        int seq = ++requestSeq;
        sender.send(new Request(seq, currentDimensionId, trimmed));
    }

    /**
     * 收到服务端结果。
     *
     * <p>三重过滤，缺一不可：</p>
     * <ol>
     *   <li>序号必须还是最新的一次 —— 打字快时旧响应会晚到；</li>
     *   <li>维度必须与当前维度一致 —— 切换维度后旧结果无意义；</li>
     *   <li>已提交的查询必须仍等于当前输入 —— 序号对得上但用户又改了字，结果同样不能用。</li>
     * </ol>
     *
     * <p>通过过滤后把本地别名与服务端结果合并：同一个坐标只保留一条，
     * 且总数不超过 {@code maxResults}。本地条目在前，服务端只补缺。</p>
     */
    public void acceptResults(int seq,
                              ResourceLocation dimensionId,
                              ResourceLocation currentDimensionId,
                              boolean success,
                              List<MapSearchResult> serverResults) {
        if (seq != requestSeq || dimensionId == null || !dimensionId.equals(currentDimensionId)) return;
        if (!submittedQuery.equals(query.trim())) return;
        loading = false;
        startedAtMs = 0L;
        failed = !success;
        if (!success) return;

        LinkedHashMap<Long, MapSearchResult> merged = new LinkedHashMap<>();
        for (MapSearchResult local : localLookup.find(query)) {
            merged.put(resultKey(local.pos()), local);
        }
        if (serverResults != null) {
            for (MapSearchResult result : serverResults) {
                if (result == null || result.pos() == null) continue;
                if (!merged.containsKey(resultKey(result.pos())) && merged.size() >= maxResults) break;
                merged.put(resultKey(result.pos()), result);
            }
        }
        results = List.copyOf(merged.values());
    }

    /** 一个坐标在结果列表里的稳定键。x/z 各占 32 位，负数也能区分。 */
    public static long resultKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
    }
}
