/* 文件职责：结构避让的诊断计数器（从 {@link StructureAvoidanceService} 拆出）。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 结构避让的诊断计数器（从 {@link StructureAvoidanceService} 拆出）。
 *
 * <p>这些计数器**只参与日志/取证，不参与任何避让决策**。把它们单独成类，是为了
 * 让上帝类 {@link StructureAvoidanceService} 少背一份职责，也让「哪些状态是纯诊断、
 * 哪些状态是核心缓存」一目了然。</p>
 */
public final class StructureAvoidanceDiagnostics {
    private StructureAvoidanceDiagnostics() {}

    static final AtomicLong PENDING_REQUESTS = new AtomicLong(0);
    static final AtomicLong PREDICTED_DIRECT_LOOKUPS = new AtomicLong(0);
    static final AtomicLong MANUAL_REFERENCE_LOOKUPS = new AtomicLong(0);
    static final AtomicLong PREPARED_CACHE_HITS = new AtomicLong(0);
    static final AtomicLong PREPARED_EMPTY_RESULTS = new AtomicLong(0);

    /**
     * 0.9.034（R-07 计数模式）：群系预筛的只读探针。三个计数器都不参与任何决策。
     *
     * <p>{@code BIOME_PROBE_REJECTED_NONEMPTY} 是判据核心：如果「群系预筛会挡掉」的起点里有
     * 一个真的产出了盒子，那么真的开预筛就会丢结构 ⇒ 必须为 0 才能开启。</p>
     */
    static final AtomicLong BIOME_PROBE_PROBED = new AtomicLong(0);
    static final AtomicLong BIOME_PROBE_REJECTED = new AtomicLong(0);
    static final AtomicLong BIOME_PROBE_REJECTED_NONEMPTY = new AtomicLong(0);

    static final AtomicLong PREPARED_EXACT_BOXES = new AtomicLong(0);
    static final AtomicLong OBSTACLE_PREDICTION_TILES = new AtomicLong(0);
    static final AtomicLong OBSTACLE_PREDICTION_CHUNKS = new AtomicLong(0);
    static final AtomicLong OBSTACLE_TILE_CACHE_HITS = new AtomicLong(0);
    static final AtomicLong OBSTACLE_CANDIDATES = new AtomicLong(0);
    static final AtomicLong OBSTACLE_CORRIDOR_CANDIDATES = new AtomicLong(0);
    static final AtomicLong DIRECT_STRUCTURE_STARTS = new AtomicLong(0);
    static final AtomicLong DIRECT_STRUCTURE_FALLBACKS = new AtomicLong(0);

    /**
     * occupancy 缺失（只剩 fallback 盒）时被问到「撞不撞」的次数（2026-11-15 新增）。
     *
     * <p><b>为什么要单独计数：</b>{@code OwnedStructureBox.intersects} 在 occupancy 为 null 时
     * 直接返回 false，而 null 的两种原因完全同形 ——</p>
     * <ul>
     *   <li>① 这个结构本来就只有预测盒、没有模板方块数据（<b>正常</b>）；</li>
     *   <li>② 模板解析失败（<b>异常</b>，会让这条路对该结构完全失明）。</li>
     * </ul>
     * <p>第二种是真机上「路穿建筑」的病根之一，且日志里一个字都没有。
     * 有了这个数字，真机上就能判断：如果它长期远大于 0 且与建成道路数同量级，
     * 说明大量结构只剩 fallback 盒、校验其实没在工作。</p>
     * <p><b>不参与任何决策</b>，纯取证。</p>
     */
    static final AtomicLong FALLBACK_BOX_ONLY_QUERIES = new AtomicLong(0);

    static final AtomicBoolean NATIVE_DIRECT_DISABLED = new AtomicBoolean(false);
    static final AtomicInteger ACTIVE_NATIVE_DIRECT = new AtomicInteger(0);

    /** 「occupancy 缺失因而放弃精确判定」的累计次数（只读取证；见字段 javadoc）。 */
    public static long fallbackBoxOnlyQueries() {
        return FALLBACK_BOX_ONLY_QUERIES.get();
    }

    /** 重置取证计数（测试用）。 */
    static void resetFallbackBoxOnlyQueries() {
        FALLBACK_BOX_ONLY_QUERIES.set(0);
    }
}
