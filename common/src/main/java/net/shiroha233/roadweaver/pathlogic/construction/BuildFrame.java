/* 文件职责：按路缓存「建造帧」（中心线 + 规划期桥范围）—— 把每区块的重算变成一次。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.lang.ref.WeakReference;
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
 * <p>中心、站序、跨度及桥参数相同时可以复用；路线重建后重新核对缓存键与站序，
 * 不能认为同一持久道路永远不再修改。中心提取与范围运算按路复用，减少逐区块重复扫描。</p>
 *
 * <h2>缓存输入边界</h2>
 * <p>桥范围运算只读取站中心、跨度和桥参数，不读取铺装列。RoadData及分站/跨度构造器
 * 已冻结列表和坐标；本缓存也允许独立站列表输入，因此仍核对有序中心，不只相信指纹。
 * 指纹负责分桶，桥参数与跨度进入键，正向中心站序仍须匹配；修改缓存依赖输入应创建新道路。</p>
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
 * <p>容量超过阈值时调用ConcurrentHashMap.clear，保持现有整表淘汰策略。
 * 清空需要遍历内部桶，不能标为O(1)；并发插入也使该阈值不是严格容量保证。
 * 再次访问被清条目会重算，本轮未测现场抖动，不对耗时作无依据保证。</p>
 */
public final class BuildFrame {

    /** 缓存上限：超过就整表清空。 */
    private static final int MAX_ENTRIES = 512;

    // 道路指纹只用于缩小查找范围。首尾把正反向分桶，命中时仍核对准确的有序站点。
    private record Key(long fingerprint, BlockPos first, BlockPos last,
                       BridgeRangeCalculator.Settings settings, List<RoadSpan> spans) {}

    private static final class CachedFrame {
        private final Frames frame;
        private volatile WeakReference<List<RoadSegmentPlacement>> source;

        CachedFrame(Frames frame, List<RoadSegmentPlacement> source) {
            this.frame = frame;
            this.source = new WeakReference<>(source);
        }

        boolean matches(List<RoadSegmentPlacement> stations) {
            // 同一外层站列表直接命中；只缓存中心与掩码，不依赖铺装列。
            if (source.get() == stations) return true;
            if (frame.middlePositions().size() != stations.size()) return false;
            for (int i = 0; i < stations.size(); i++) {
                if (!frame.middlePositions().get(i).equals(stations.get(i).middlePos())) return false;
            }
            source = new WeakReference<>(stations);
            return true;
        }
    }

    /**
     * 一条路的建造帧。
     *
     * <h2>★ 访问器的使用纪律</h2>
     * <p>{@link #middlePositions()} 返回的是 {@code List.copyOf} 的不可变列表，
     * 元素 {@code BlockPos} 也不可变 ⇒ <b>可以直接共享，随便读</b>。</p>
     *
     * <p>但 {@link #isBridge()} / {@link #skipSegments()} / {@link #mergedRanges()}
     * <b>是缓存内部状态</b>：数组与缓存共享（只在 {@code Frames} 构造时 clone 过一次）。
     * 想拿出去改，<b>必须用 {@link #copyForUser()}</b> —— 它会深拷贝。</p>
     *
     * <p>这条纪律现在由 {@code BuildFrameTest.桥区间里的int数组也是深拷贝} 盯着。</p>
     *
     * @param middlePositions 每站中心点（{@code BlockPos} 不可变，可安全共享）
     * @param isBridge        规划期的桥掩码
     * @param mergedRanges    合并后的桥区间
     * @param skipSegments    规划期的跳过掩码
     * @param stationSpacing 不可变的水平里程，用于各区块按同一站序布灯
     */
    public record Frames(List<BlockPos> middlePositions,
                         boolean[] isBridge,
                         List<int[]> mergedRanges,
                         boolean[] skipSegments,
                         StationSpacing stationSpacing) {

        /**
         * 发一份可以随便改的副本给调用方。
         *
         * <p><b>生产路径只应使用本方法</b>，不要直接用上面三个访问器 —— 理由见类内「访问器的使用纪律」。</p>
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

    /** 同一路的里程只累加一次，避免每个候选灯位重新从路头开始扫描。 */
    public static final class StationSpacing {
        private final List<BlockPos> stations;
        private final double[] distanceFromStart;

        private StationSpacing(List<BlockPos> stations) {
            this.stations = stations;
            this.distanceFromStart = new double[stations.size()];
            for (int i = 1; i < stations.size(); i++) {
                distanceFromStart[i] = distanceFromStart[i - 1]
                        + horizontalDistance(stations.get(i - 1), stations.get(i));
            }
        }

        public boolean nearestToMultiple(int index, int interval) {
            if (index <= 0 || index >= stations.size() - 1) return false;
            int spacing = Math.max(1, interval);
            double current = distanceFromStart[index];
            long bucket = Math.round(current / spacing);
            if (bucket <= 0L) return false;
            double target = bucket * (double) spacing;
            // 保留旧判定的减法和并列规则，避免浮点舍入改变路灯位置和随机序列。
            double previous = current - horizontalDistance(stations.get(index - 1), stations.get(index));
            double next = distanceFromStart[index + 1];
            return Math.abs(current - target) <= Math.abs(previous - target)
                    && Math.abs(current - target) <= Math.abs(next - target);
        }

        private static double horizontalDistance(BlockPos a, BlockPos b) {
            long dx = (long) b.getX() - a.getX();
            long dz = (long) b.getZ() - a.getZ();
            return Math.sqrt((double) dx * dx + (double) dz * dz);
        }
    }

    private static final Map<Key, CachedFrame> CACHE = new ConcurrentHashMap<>();

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
        // RoadData 已用 List.copyOf 冻结列表；常见命中不会复制站位或重扫中心线。
        List<RoadSegmentPlacement> stations = List.copyOf(segments);
        List<RoadSpan> spanSnapshot = spans == null ? List.of() : List.copyOf(spans);
        var settings = BridgeRangeCalculator.Settings.from(cfg);
        Key key = new Key(fingerprint, stations.isEmpty() ? null : stations.getFirst().middlePos(),
                stations.isEmpty() ? null : stations.getLast().middlePos(), settings, spanSnapshot);
        CachedFrame cached = CACHE.get(key);
        if (cached != null && cached.matches(stations)) return cached.frame;
        return CACHE.compute(key, (k, entry) -> entry != null && entry.matches(stations) ? entry
                : new CachedFrame(compute(stations, spanSnapshot, settings), stations)).frame;
    }

    private static Frames compute(List<RoadSegmentPlacement> segments, List<RoadSpan> spans,
                                  BridgeRangeCalculator.Settings settings) {
        List<BlockPos> middlePositions = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement s : segments) {
            middlePositions.add(s.middlePos());
        }
        BridgeRangeCalculator.RangeResult r = BridgeRangeCalculator.compute(settings, middlePositions, spans);
        List<BlockPos> centers = List.copyOf(middlePositions);
        return new Frames(centers, r.isBridge().clone(),
                List.copyOf(r.mergedRanges()), r.skipSegments().clone(), new StationSpacing(centers));
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
