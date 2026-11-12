/* 文件职责：按路缓存「建造帧」（中心线 + 规划期桥范围）—— 把每区块的重算变成一次。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeRangeCalculator;

/**
 * 建造帧缓存：一条路的「中心线 + 规划期桥范围」只算一次，之后每个区块直接读。
 *
 * <h2>为什么需要它</h2>
 * <p>{@code PathFeature} 是区块生成入口，<b>每个区块</b>都会跑一遍：</p>
 * <pre>
 *   middlePositions      = segments.map(middlePos)              // O(站数)
 *   plannedBridgeResult  = BridgeRangeCalculator.compute(...)   // O(站数)，扫全条路
 * </pre>
 * <p>而这两个东西<b>对同一条路永远算出一样的结果</b> —— 输入是 {@code data.spans()} 与
 * {@code data.width()}，路一旦落盘就不再变。于是出现 {@code O(区块数 × 站数)}：
 * 一条 2000 站的路铺满 30×30 区块，桥范围判定要把 2000 个站扫 900 次。</p>
 *
 * <h2>为什么可以缓存（正确性依据）</h2>
 * <ul>
 *   <li>{@code BridgeRangeCalculator.compute(positions, spans, cfg)} 是<b>纯函数</b>：
 *       不读世界、不写参数、没有随机；</li>
 *   <li>缓存 key = {@code RoadShardStorage.computeFingerprint(data)}（<b>内容指纹</b>，
 *       不是实例身份）+ 配置对象 —— 内容变了指纹就变，天然不会读到旧值；</li>
 *   <li>路数据是<b>不可变</b>的（{@code RoadData} 是 record 且紧凑构造器做了
 *       {@code List.copyOf}），不存在「同一条路被就地改写」。</li>
 * </ul>
 *
 * <h2>★ 为什么必须深拷贝出去</h2>
 * <p>{@code RangeResult} 里装的是 {@code boolean[]} 与 {@code List<int[]>} ——
 * <b>可变数组</b>。区块生成里会按需要就地改这些掩码；如果把缓存里的同一份数组发出去，
 * 第二个区块就会读到被第一个区块改过的掩码。那种 bug 只在「同一区块附近生成顺序特殊」
 * 时出现，极难复现。</p>
 *
 * <p>所以每次 {@link Frames#copyForUser()} 都深拷贝：两个 {@code boolean[]} 各 clone，
 * {@code int[]} 逐个 clone。拷贝量是每区块 2n+b 个基本类型，相对重算可以忽略，
 * 但换来的是「共享的是不可变值」这条硬保证。</p>
 *
 * <h2>缓存容量</h2>
 * <p>上限 {@value #MAX_ENTRIES} 条路，超了整表清空。<b>不做 LRU</b>：
 * 清空是 O(1) 且不会出现「某条路被淘汰后立刻又要用」的抖动放大；
 * 而区块生成的访问模式本来就是「一段时间专注一片区域」，整表清空的代价可以接受。</p>
 */
public final class BuildFrame {

    /** 缓存上限：超过就整表清空。 */
    private static final int MAX_ENTRIES = 512;

    /** 缓存键：内容指纹 + 配置对象。两者任一变化都必须重算。 */
    private record Key(long fingerprint, ModConfig cfg) {
    }

    /**
     * 一条路的建造帧。
     *
     * @param middlePositions 每站中心点（{@code BlockPos} 不可变，可安全共享）
     * @param isBridge        规划期的桥掩码
     * @param mergedRanges    合并后的桥区间
     * @param skipSegments    规划期的跳过掩码
     */
    public record Frames(List<BlockPos> middlePositions,
                         boolean[] isBridge,
                         List<int[]> mergedRanges,
                         boolean[] skipSegments) {

        /**
         * 发一份可以随便改的副本给调用方。
         *
         * <p>深拷贝是刻意的 —— 见类注释「为什么必须深拷贝出去」。</p>
         */
        public BridgeRangeCalculator.RangeResult copyForUser() {
            List<int[]> ranges = new ArrayList<>(mergedRanges.size());
            for (int[] r : mergedRanges) {
                ranges.add(r == null ? null : r.clone());
            }
            return new BridgeRangeCalculator.RangeResult(isBridge.clone(), ranges, skipSegments.clone());
        }
    }

    private static final Map<Key, Frames> CACHE = new ConcurrentHashMap<>();

    private BuildFrame() {
    }

    /**
     * 取（必要时算）某条路的建造帧。
     *
     * @param fingerprint {@code RoadShardStorage.computeFingerprint(data)} 的结果
     */
    public static Frames of(long fingerprint, List<RoadSegmentPlacement> segments,
                            List<RoadSpan> spans, ModConfig cfg) {
        if (CACHE.size() > MAX_ENTRIES) CACHE.clear();
        Key key = new Key(fingerprint, cfg);
        return CACHE.computeIfAbsent(key, k -> compute(segments, spans, cfg));
    }

    private static Frames compute(List<RoadSegmentPlacement> segments, List<RoadSpan> spans, ModConfig cfg) {
        List<BlockPos> middlePositions = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement s : segments) {
            middlePositions.add(s.middlePos());
        }
        BridgeRangeCalculator.RangeResult r = BridgeRangeCalculator.compute(middlePositions, spans, cfg);
        return new Frames(List.copyOf(middlePositions), r.isBridge().clone(),
                List.copyOf(r.mergedRanges()), r.skipSegments().clone());
    }

    /** 清空缓存（配置热重载、测试用）。 */
    public static void clear() {
        CACHE.clear();
    }

    /** 当前缓存条目数（诊断/测试用）。 */
    public static int size() {
        return CACHE.size();
    }
}