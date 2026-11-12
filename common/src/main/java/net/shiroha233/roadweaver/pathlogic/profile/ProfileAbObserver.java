/* 文件职责：剖面 AB 双跑观察器 —— 只观察、只记日志，绝不改变返回的剖面。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 剖面 AB 双跑观察器。
 *
 * <h2>它做什么</h2>
 * <p>在旧版剖面算完之后，<b>另外</b>跑一次全局 DP，把两者的
 * {@link ProfileMetrics} 并排打进日志。旧版结果照旧返回，
 * <b>一个字节都不改</b> —— 这也是它能安全上线的唯一原因。</p>
 *
 * <h2>为什么先观察而不是直接切换</h2>
 * <p>用户的铁律是「行为变更必须与旧版逐项对照」。直接切默认等于拿玩家存档做实验：
 * 剖面矮一格就是看得见的地形改动，而且旧存档里已经建好的路不会跟着变，
 * 新旧剖面在接缝处会打架。所以顺序必须是：</p>
 * <ol>
 *   <li><b>观察</b>（本类）：在真实路线上并排记录，谁更好由数据说话；</li>
 *   <li><b>解释差异</b>：新解更差的那些路，差在哪一段、为什么；</li>
 *   <li><b>再切默认</b>。</li>
 * </ol>
 *
 * <h2>怎么打开</h2>
 * <pre>-Droadweaver.profileAb=on</pre>
 * <p>默认关闭，关闭时本类在第一次判断后就返回，零开销。</p>
 *
 * <h2>坑</h2>
 * <p>DP 求解失败必须是<b>可解释的事件</b>而不是异常。地形太陡、窗口太窄都会让问题不可行，
 * 那是参数问题不是 bug —— 所以这里只记一次「不可行」，并且带上原因，
 * 而不是每 16 条路刷一次让人以为出事了。</p>
 */
public final class ProfileAbObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver");
    private static final String PROPERTY = "roadweaver.profileAb";

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger INFEASIBLE = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    private ProfileAbObserver() {
    }

    /** 是否启用（每次读系统属性，便于运行期开关；代价可忽略）。 */
    public static boolean enabled() {
        String v = System.getProperty(PROPERTY, "off");
        return "on".equalsIgnoreCase(v) || "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /**
     * 观察一条路。
     *
     * @param legacyProfile 旧版算出来的剖面（本方法绝不修改它）
     * @param terrainY      每站地面高度
     * @param crossing      每站是否位于桥/跨水上
     * @param maxCut        DP 试验窗口：允许挖多深
     * @param maxFill       DP 试验窗口：允许填多高
     * @param maxStep       DP 试验约束：相邻站最大落差
     * @param tag           标识（连接端点），便于回查是哪条路
     */
    public static void observe(int[] legacyProfile, int[] terrainY, boolean[] crossing,
                               int maxCut, int maxFill, int maxStep, String tag) {
        if (!enabled()) return;
        if (legacyProfile == null || terrainY == null || legacyProfile.length != terrainY.length) return;
        try {
            Optional<int[]> dp = ProfileDpSource.solve(terrainY, crossing, maxCut, maxFill, maxStep);
            int call = CALLS.incrementAndGet();
            if (dp.isEmpty()) {
                int bad = INFEASIBLE.incrementAndGet();
                if (bad <= 3 || bad % 32 == 0) {
                    LOGGER.info("[PROFILE-AB] infeasible stations={} window=-{}/+{} maxStep={} tag={} (累计不可行 {} 条)",
                            legacyProfile.length, maxCut, maxFill, maxStep, tag, bad);
                }
                return;
            }
            ProfileMetrics a = ProfileMetrics.of(legacyProfile, terrainY, crossing);
            ProfileMetrics b = ProfileMetrics.of(dp.get(), terrainY, crossing);
            if (call <= 8 || call % 16 == 0) {
                LOGGER.info("[PROFILE-AB] #{} stations={} tag={} legacy[{}] dp[{}] {}",
                        call, legacyProfile.length, tag, a.line(), b.line(), a.versus(b));
            }
        } catch (Throwable t) {
            // 观察器绝不能影响主流程
            int bad = FAILED.incrementAndGet();
            if (bad <= 3) {
                LOGGER.warn("[PROFILE-AB] 观察失败（已忽略，不影响主流程）：{}", t.toString());
            }
        }
    }

    // ── S4：模式联合求解的 AB 观察 ─────────────────────────────

    /** 模式 AB 实验参数。单价是实验值，不是生产值 —— 见各字段注释。 */
    private static final ModeDpSolver.Settings MODE_AB_SETTINGS = new ModeDpSolver.Settings(
            24, 12, 16, 48, 6,
            // 桥每站 / 隧道每站 / 桥台 / 洞口：取「桥明显比隧道便宜」的真实直觉。
            5.0, 14.0, 3.0, 6.0,
            CostModel.Settings.defaults());

    private static final AtomicInteger MODE_CALLS = new AtomicInteger();
    private static final AtomicInteger MODE_INFEASIBLE = new AtomicInteger();
    private static final AtomicInteger MODE_FAILED = new AtomicInteger();

    /**
     * 模式 AB：在<b>同样的物理约束</b>下，让联合 DP 自己重新决定桥/隧道/地面。
     *
     * <h2>它回答什么问题</h2>
     * <p>旧版先判桥再算剖面。这里把旧版判出来的「不可铺地面」当作
     * <b>物理事实</b>喂进去（水面、已有公路、保护区），然后问一个问题：</p>
     *
     * <blockquote>在同样的物理约束下，联合求解认为需要几座桥、几条隧道？</blockquote>
     *
     * <p>如果联合求解认为「完全不需要桥」，而旧版判了 3 座，那 3 座就是
     * 判定与剖面互相打架的产物 —— 这就是 S4 可能的收益。</p>
     *
     * <h2>为什么不喂旧版的桥/隧道选择</h2>
     * <p>那会让 DP 复现旧版的决策，对比就没意义了。喂进去的只有
     * <b>「这里不能铺地面」</b>这一条物理约束，桥还是隧道由 DP 自己权衡。</p>
     *
     * @param groundBanned 旧版认为不可铺地面的站（水面 / 已有公路 / 保护区）
     * @param legacyTunnel 旧版判为隧道的站（仅用于对比展示，不参与求解）
     */
    public static void observeModes(int[] legacyProfile, int[] terrainY, boolean[] groundBanned,
                                    boolean[] legacyTunnel, int maxStep, String tag) {
        if (!enabled()) return;
        if (terrainY == null || legacyProfile == null || legacyProfile.length != terrainY.length) return;
        try {
            java.util.Optional<ModeDpSolver.Solution> dp =
                    ModeDpSolver.solve(terrainY, Math.max(1, maxStep), MODE_AB_SETTINGS, groundBanned);
            int call = MODE_CALLS.incrementAndGet();
            if (dp.isEmpty()) {
                int bad = MODE_INFEASIBLE.incrementAndGet();
                if (bad <= 3 || bad % 32 == 0) {
                    LOGGER.info("[PROFILE-AB-MODE] infeasible stations={} tag={} (累计 {} 条)",
                            terrainY.length, tag, bad);
                }
                return;
            }
            ModeDpSolver.Solution s = dp.get();
            int legacyBridge = 0;
            int legacyTunnelCount = 0;
            for (int i = 0; i < legacyTunnel.length; i++) {
                if (legacyTunnel[i]) legacyTunnelCount++;
            }
            for (int i = 0; i < groundBanned.length; i++) {
                if (groundBanned[i]) legacyBridge++;
            }
            if (call <= 8 || call % 16 == 0) {
                LOGGER.info("[PROFILE-AB-MODE] #{} stations={} tag={} "
                                + "legacy[bannedGround={} tunnel={}] dp[bridge={}({}座) tunnel={}({}条) cost={}]",
                        call, terrainY.length, tag,
                        legacyBridge, legacyTunnelCount,
                        s.stationCount(ModeDpSolver.Mode.BRIDGE), s.runCount(ModeDpSolver.Mode.BRIDGE),
                        s.stationCount(ModeDpSolver.Mode.TUNNEL), s.runCount(ModeDpSolver.Mode.TUNNEL),
                        String.format("%.1f", s.totalCost()));
            }
        } catch (Throwable t) {
            int bad = MODE_FAILED.incrementAndGet();
            if (bad <= 3) {
                LOGGER.warn("[PROFILE-AB-MODE] 观察失败（已忽略，不影响主流程）：{}", t.toString());
            }
        }
    }

    /** 模式观察的累计次数（诊断/测试用）。 */
    public static int modeCalls() {
        return MODE_CALLS.get();
    }

    /** 累计调用次数（诊断/测试用）。 */
    public static int calls() {
        return CALLS.get();
    }
}