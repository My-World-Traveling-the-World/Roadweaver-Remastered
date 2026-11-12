/* 文件职责：0.9.059（②）候选结构枚举范围（candidateBounds）的自适应扩张——纯函数，不读世界；0.9.060 起余量按核心区对角线相对取值（带面积守卫）。 */
package net.shiroha233.roadweaver.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 0.9.059（②）+ 0.9.060（②）：{@code candidateBounds} 的自适应扩张与预热规模保护。
 *
 * <h2>0.9.059 为什么改</h2>
 * <p>旧口径把候选范围按 {@code planMaxEdgeLenBlocks}(默认 2048) 从 {@code coreBounds} 四边外扩，
 * 2048×2048 的核心区变成 6144×6144 的枚举区（面积 9:1）。真机一局：
 * {@code topologyMs=1,064 structureSnapshotsMs=18,167 terrainPlanningMs=118,542}，其中
 * {@code terrainSnapshotPrepMs=72,670}；{@code structures=30 connections=4 total=4,374
 * emptyResults=3,997(91.4%)} —— 4374 个候选起点里九成解析完是空的，而这些起点几乎全来自
 * 「横跨 6 km 的长路 → 走廊包围盒巨大 → 障碍瓦片扫描面积爆炸」。</p>
 *
 * <h2>0.9.059 的余量（已被 0.9.060 替换，留档）</h2>
 * <ol>
 *   <li>初始枚举范围 = {@code coreBounds} 外扩<b>固定 256 格</b>；</li>
 *   <li>正常规划一轮；</li>
 *   <li>跑完看<b>每条路实际走线的包围盒</b>，只有越出当前余量的那些路会被退回重规划，
 *       范围按 {@link #EXPANSION_FACTOR} 扩一档（256 → 1024 → ≤ {@code capMargin}）。</li>
 * </ol>
 *
 * <h2>0.9.060：那个 256 为什么会反噬</h2>
 * <p>256 是<b>绝对值</b>，而它加在一个会随预热范围变化的 {@code coreBounds} 上：
 * 预热 256 区块（半径 4096 格）时核心区约 8192×8192，候选 = 8704×8704；预热 2048 区块时
 * 核心区约 65536×65536，候选 = <b>66048×66048</b> —— 早就不是「收窄」，而是比 0.9.058
 * 之前的固定 6144 还大十倍的枚举区。所以余量必须跟着核心区的<b>尺度</b>走，而不是钉死一个格数。</p>
 *
 * <h2>0.9.060 的新口径（全部是常量，不开配置面）</h2>
 * <pre>
 * margin        = clamp( round(hypot(coreW, coreD) × MARGIN_RATIO), MARGIN_FLOOR, MARGIN_CAP )
 * tripped       = (coreW + 2×margin) × (coreD + 2×margin) &gt; MAX_CANDIDATE_AREA_BLOCKS
 * finalMargin   = tripped ? min(margin, 能落进 areaCap 的最大整数余量) : margin
 *                 其中「能落进的最大整数余量」&lt; MARGIN_FLOOR 时取 MARGIN_FLOOR
 * candidateRect = coreBounds 四边各外扩 finalMargin   // 因此恒有 candidate ⊇ core
 * </pre>
 * <p>{@link #MARGIN_FLOOR}(512) / {@link #MARGIN_CAP}(4096) 保证余量随核心区单调增长但有界：
 * 小核心（如真机 2048×2048）得到 724、4 倍核心得到 1448、8 倍核心触及上限 4096。
 * {@link #MAX_CANDIDATE_AREA_BLOCKS}(8192²=67,108,864) 是<b>预热规模保护</b>的判据：
 * 它只允许收窄<b>外扩出来的 margin</b>，绝不允许裁 {@code coreBounds} 本身。</p>
 *
 * <h2>正确性红线：core 永远完整枚举</h2>
 * <p>结构枚举的矩形是 {@code candidateBounds}，而它按构造就是 {@code coreBounds} 的四边各加
 * 一个非负 margin（{@link #boundsWithMargin}）⇒ <b>core 内的每一个方块坐标都落在候选矩形内</b>，
 * 与 margin 取多少、guard 有没有触发完全无关。guard 触发的极端情形（core 自身面积就超过
 * areaCap，例如 32768×32768 = 1.07e9 &gt; 6.7e7）也只会把 margin 压到 {@link #MARGIN_FLOOR}，
 * 不会把矩形收到 core 里面去。{@code AdaptiveCandidateBoundsScaleTest} 用四档预热规模逐档钉住
 * 这一条（含「core 面积自身就超 cap」的那三档）。</p>
 *
 * <h2>等价性（0.9.059 的论证仍然成立，写在这里，集中一处）</h2>
 * <p>本类只做一个决定：<b>把结构枚举矩形放大到多少</b>。它不读世界、不改路径、不改避让，
 * 所以"是否等价"完全可以在矩形层面判定，不需要跑世界：</p>
 * <ol>
 *   <li><b>admission 等价（可证、有单测）</b>：结构枚举本身就是一个纯矩形谓词 ——
 *       {@code RoadPlanningService.collectStructurePointsInto} 里逐点做
 *       {@code if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) continue;}
 *       （见该方法的 522 行）。因此对任意结构集合 {@code S} 与初始矩形 {@code I} ⊆
 *       全量矩形 {@code F}，恒有
 *       <pre>{ s ∈ S : s ∈ I } == { s ∈ S : s ∈ F } ∩ I</pre>
 *       也就是说<b>初始余量内的结构，在初始档与全量档里被枚举到的是同一批，一个都不会少</b>。
 *       "范围缩小"唯一可能伤害合规路的途径就是"少看了它附近的结构"，这条被上式排除。</li>
 *   <li><b>不复算等价（结构保证、有单测）</b>：只有 {@link PathBounds#within(Rect)} 为
 *       {@code false} 的路才会被 {@code rewind} 后进入下一轮；走线落在当前余量内的路
 *       <b>不参与任何后续轮次</b>，最终发布的就是第一轮算出来的那份 {@code List<BlockPos>}
 *       （引用都不换），所以"合规路的结果"= 第一轮的结果，逐位相同。</li>
 *   <li><b>已知边界（不承诺，别当已证）</b>：第一轮"参与规划的路集合"比全量档小，而
 *       {@code CorridorAttract} / {@code CrossingNegotiation} 是<b>跨路</b>耦合
 *       （{@code CoarseCorridorPlanningAdapter:179} 把当批已算完的路挂成优惠走廊）。
 *       因此"第一轮的合规路"与"旧的 6144 全量档里同一条路"是否逐位相同，还取决于
 *       <b>有没有被丢掉的长路会穿过它自己的走廊</b>。这是本机制唯一无法在矩形层面证明的
 *       依赖；{@code [PLAN-ADAPTIVE] roadsExpanded=} 为 0 只说明不需要扩张，
 *       不等于与旧全量档逐位相同。</li>
 * </ol>
 *
 * <h2>为什么这些数不是配置项</h2>
 * <p>余量、比例、上下限、面积上限、档位倍数、最多几档都是"实现内部常数"：它们只决定
 * "先看多少、什么时候加看、最多看多大"，不改变任何一条路的判据。开放成配置面只会让
 * "等价性"多一个不可测的维度。</p>
 */
public final class AdaptiveCandidateBounds {

    /**
     * 0.9.060：初始余量 = {@code coreBounds} 的 XZ 对角线 × 该比例（再按
     * {@link #MARGIN_FLOOR} / {@link #MARGIN_CAP} 夹住）。
     *
     * <p>取 0.25 是<b>工程折中，不是实测标定</b>：它让余量随核心区线性放大
     * （4 倍核心 ⇒ 4 倍余量），同时 2048×2048 的真机核心得到 724 格（仍在
     * 0.9.059 的 256 与旧 2048 之间，收窄收益保留、边缘结构仍连得上）。
     * 依据与风险写在 0.9.060 的交付报告里，不要把它当"已验证的最优值"。</p>
     */
    public static final double MARGIN_RATIO = 0.25;

    /**
     * 余量下限（格）。核心区再小、或 guard 再怎么收窄，余量也不低于这个值。
     *
     * <p>取 512 而不是 0：最小的预热档（256 区块 / 半径 4096 格）核心区也有 8192 格宽，
     * 边缘外的村庄往往就是自然延伸的下一站；余量掉到 0 会让边缘结构连不出去。</p>
     */
    public static final int MARGIN_FLOOR = 512;

    /**
     * 余量上限（格）。核心区再大，单边也只外扩这么多，避免「核心区 16 倍 ⇒ 余量 16 倍」。
     */
    public static final int MARGIN_CAP = 4096;

    /**
     * 候选区面积上限（方块格数）：8192 × 8192 = 67,108,864。
     *
     * <p>这是<b>预热规模保护</b>的判据，不是正确性约束：超过它时只收窄外扩的
     * {@link #MARGIN_FLOOR margin}，{@code coreBounds} 内的枚举量一格都不裁（红线）。</p>
     */
    public static final long MAX_CANDIDATE_AREA_BLOCKS = 8192L * 8192L;

    /** 一档扩张的倍数：余量 × 该值（例如 724 → 2048 被 {@code capMargin} 截住）。 */
    public static final int EXPANSION_FACTOR = 4;

    private AdaptiveCandidateBounds() {}

    /** 轴对齐矩形（含边界）。纯 int，可在没有 Minecraft 运行时的测试里直接用。 */
    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public static Rect of(int[] bounds) {
            int minX = Math.min(bounds[0], bounds[2]);
            int minZ = Math.min(bounds[1], bounds[3]);
            int maxX = Math.max(bounds[0], bounds[2]);
            int maxZ = Math.max(bounds[1], bounds[3]);
            return new Rect(minX, minZ, maxX, maxZ);
        }

        public int[] toArray() {
            return new int[]{minX, minZ, maxX, maxZ};
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** X 方向格数（含两端）。用 long 以免 {@code maxX - minX + 1} 在极端值上溢出。 */
        public long widthBlocks() {
            return (long) maxX - minX + 1L;
        }

        /** Z 方向格数（含两端）。 */
        public long depthBlocks() {
            return (long) maxZ - minZ + 1L;
        }

        /** {@code this} 是否完全包含 {@code other}。 */
        public boolean containsAll(Rect other) {
            return other != null
                    && other.minX >= minX && other.maxX <= maxX
                    && other.minZ >= minZ && other.maxZ <= maxZ;
        }

        public long area() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        public boolean sameAs(Rect other) {
            return other != null
                    && other.minX == minX && other.maxX == maxX
                    && other.minZ == minZ && other.maxZ == maxZ;
        }

        @Override
        public String toString() {
            return "[" + minX + "," + minZ + " -> " + maxX + "," + maxZ + "]";
        }
    }

    /**
     * 一条已规划路的走线包围盒（只 XZ）。用来回答"这条路有没有越出当前枚举余量"。
     */
    public record PathBounds(int minX, int minZ, int maxX, int maxZ) {
        /**
         * 从等长的 X/Z 数组构造；空数组返回 {@code null}（没有走线，不参与判定）。
         * 只做一遍扫描，O(n)。
         */
        public static PathBounds of(int[] xs, int[] zs) {
            if (xs == null || zs == null || xs.length == 0 || zs.length == 0) return null;
            int n = Math.min(xs.length, zs.length);
            int minX = xs[0], maxX = xs[0], minZ = zs[0], maxZ = zs[0];
            for (int i = 1; i < n; i++) {
                int x = xs[i];
                int z = zs[i];
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
            return new PathBounds(minX, minZ, maxX, maxZ);
        }

        public static PathBounds of(List<int[]> points) {
            if (points == null || points.isEmpty()) return null;
            int[] xs = new int[points.size()];
            int[] zs = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                int[] p = points.get(i);
                xs[i] = p == null ? 0 : p[0];
                zs[i] = p == null ? 0 : p[1];
            }
            return of(xs, zs);
        }

        /** 四条边都在 {@code bounds} 内（含边界）。 */
        public boolean within(Rect bounds) {
            return bounds != null && overshoot(bounds) == 0;
        }

        /** 越出 {@code bounds} 的最大格数；完全在内时返回 0（不是负数）。 */
        public int overshoot(Rect bounds) {
            if (bounds == null) return 0;
            int out = 0;
            if (minX < bounds.minX()) out = Math.max(out, bounds.minX() - minX);
            if (minZ < bounds.minZ()) out = Math.max(out, bounds.minZ() - minZ);
            if (maxX > bounds.maxX()) out = Math.max(out, maxX - bounds.maxX());
            if (maxZ > bounds.maxZ()) out = Math.max(out, maxZ - bounds.maxZ());
            return out;
        }
    }

    /**
     * 0.9.060：一次余量解析 + 面积守卫判定的完整结果（纯值，可直接单测/直接打日志）。
     *
     * <p>字段口径（也是 {@code [PLAN-BOUNDS-GUARD]} 一行的口径）：</p>
     * <ul>
     *   <li>{@code requestedMargin} = {@link #marginFor} 的结果，<b>还没被 guard 收窄</b>；</li>
     *   <li>{@code candidateWidth/Depth} = 按 {@code requestedMargin} 算出来的候选边长
     *       （也就是"如果不守卫会有多大"）；</li>
     *   <li>{@code tripped} = 上面这个候选面积是否超过 {@link #MAX_CANDIDATE_AREA_BLOCKS}；</li>
     *   <li>{@code finalMargin} = 实际使用的余量（tripped 时被收窄，但不低于
     *       {@link #MARGIN_FLOOR}，且永不为负 ⇒ 候选矩形恒包含 core）。</li>
     * </ul>
     */
    public record CandidateGuard(long coreWidth,
                                 long coreDepth,
                                 double coreDiagonal,
                                 int requestedMargin,
                                 long candidateWidth,
                                 long candidateDepth,
                                 long areaCap,
                                 boolean tripped,
                                 int finalMargin) {

        /** 最终候选边长（按 {@code finalMargin}）。 */
        public long finalWidth() {
            return coreWidth + 2L * finalMargin;
        }

        /** 最终候选边长（按 {@code finalMargin}）。 */
        public long finalDepth() {
            return coreDepth + 2L * finalMargin;
        }

        /** 最终候选面积（可能仍超 cap —— 当 core 自身就超 cap 时，红线优先）。 */
        public long finalArea() {
            return candidateArea(coreWidth, coreDepth, finalMargin);
        }

        /** core 自身面积是否已超 cap：此时 guard 无论如何都不可能把候选压进 cap（红线优先）。 */
        public boolean coreAloneExceedsCap() {
            return candidateArea(coreWidth, coreDepth, 0) > areaCap;
        }

        /** 一行 {@code [PLAN-BOUNDS-GUARD]}（tripped 时由调用方按 warn 打出）。 */
        public String describe() {
            return String.format(Locale.ROOT,
                    "[PLAN-BOUNDS-GUARD] coreW=%d coreD=%d coreDiag=%.2f margin=%d "
                            + "candidateW=%d candidateD=%d areaCap=%d tripped=%b finalMargin=%d",
                    coreWidth, coreDepth, coreDiagonal, requestedMargin,
                    candidateWidth, candidateDepth, areaCap, tripped, finalMargin);
        }

        /** {@code [PLAN-ADAPTIVE]} 行里的五个相对字段（顺序即约定）。 */
        public String diagnostics() {
            return String.format(Locale.ROOT,
                    "coreDiag=%.2f marginRatio=%.2f margin=%d guardTripped=%b finalMargin=%d",
                    coreDiagonal, MARGIN_RATIO, requestedMargin, tripped, finalMargin);
        }
    }

    /** {@code coreBounds} 的 XZ 对角线长度（{@code hypot(width, depth)}）。 */
    public static double coreDiagonal(int[] coreBounds) {
        Rect core = Rect.of(coreBounds);
        return Math.hypot((double) core.widthBlocks(), (double) core.depthBlocks());
    }

    /**
     * 0.9.060：按核心区尺度算出的余量 ——
     * {@code clamp(round(coreDiagonal × MARGIN_RATIO), MARGIN_FLOOR, MARGIN_CAP)}。
     *
     * <p>纯函数、常量全在本类，与 {@link #initial} / {@link #boundsWithMargin} 同一风格：
     * 给定 {@code coreBounds} 就能算，不需要世界、不需要配置。</p>
     */
    public static int marginFor(int[] coreBounds) {
        return clampMargin(Math.round(coreDiagonal(coreBounds) * MARGIN_RATIO));
    }

    /** 用 {@link #marginFor} 作为请求余量，跑一次面积守卫。 */
    public static CandidateGuard guard(int[] coreBounds) {
        return guard(coreBounds, marginFor(coreBounds));
    }

    /**
     * 0.9.060：面积守卫。{@code requestedMargin} 通常来自 {@link #marginFor}。
     *
     * <p>入参先夹到 {@code [MARGIN_FLOOR, MARGIN_CAP]}（与 {@link #marginFor} 同域，防止
     * 调用方传进 0 或超上限）。返回值保证：{@code finalMargin} 恒在
     * {@code [MARGIN_FLOOR, requested]} 内、{@code finalMargin <= requested}（守卫只收窄）、
     * <b>永不裁 core</b>。</p>
     */
    public static CandidateGuard guard(int[] coreBounds, int requestedMargin) {
        Rect core = Rect.of(coreBounds);
        long coreW = core.widthBlocks();
        long coreD = core.depthBlocks();
        int requested = clampMargin((long) requestedMargin);
        long candidateW = coreW + 2L * requested;
        long candidateD = coreD + 2L * requested;
        long area = candidateArea(coreW, coreD, requested);
        boolean tripped = area > MAX_CANDIDATE_AREA_BLOCKS;
        int finalMargin = tripped ? marginToFitCap(coreW, coreD, requested) : requested;
        return new CandidateGuard(coreW, coreD, coreDiagonal(coreBounds), requested,
                candidateW, candidateD, MAX_CANDIDATE_AREA_BLOCKS, tripped, finalMargin);
    }

    /** {@code coreBounds}（任意顺序的 {@code [minX,minZ,maxX,maxZ]}）四边各外扩 {@code margin}。 */
    public static Rect boundsWithMargin(int[] coreBounds, int margin) {
        Rect core = Rect.of(coreBounds);
        int m = Math.max(0, margin);
        return new Rect(
                satSub(core.minX(), m), satSub(core.minZ(), m),
                satAdd(core.maxX(), m), satAdd(core.maxZ(), m));
    }

    /**
     * 初始枚举范围 = {@code coreBounds} 外扩<b>经 guard 收窄后的相对余量</b>
     * （{@link #guard} 的 {@code finalMargin}）。恒包含 {@code coreBounds}。
     */
    public static Rect initial(int[] coreBounds) {
        return boundsWithMargin(coreBounds, guard(coreBounds).finalMargin());
    }

    /** 下一档余量：× {@link #EXPANSION_FACTOR}，但不超过 {@code capMargin}。已到顶则原样返回。 */
    public static int nextMargin(int margin, int capMargin) {
        int cap = Math.max(MARGIN_FLOOR, capMargin);
        long grown = (long) Math.max(0, margin) * EXPANSION_FACTOR;
        int next = (int) Math.min(Integer.MAX_VALUE, Math.max(margin, grown));
        return Math.min(cap, next);
    }

    /**
     * 这一档里有多少条路的走线越出了 {@code bounds}。0 表示不需要扩张。
     *
     * <p>null / 空列表按 0 处理（没有路就没有越界，除调用方另有要求）。</p>
     */
    public static int roadsOutside(Rect bounds, List<PathBounds> roads) {
        if (bounds == null || roads == null || roads.isEmpty()) return 0;
        int count = 0;
        for (PathBounds road : roads) {
            if (road != null && !road.within(bounds)) count++;
        }
        return count;
    }

    /** 越界路里最大的越界格数；没有越界（或没有路）返回 0。诊断行用它写 {@code overshoot=}。 */
    public static int maxOvershoot(Rect bounds, List<PathBounds> roads) {
        if (bounds == null || roads == null || roads.isEmpty()) return 0;
        int max = 0;
        for (PathBounds road : roads) {
            if (road != null) max = Math.max(max, road.overshoot(bounds));
        }
        return max;
    }

    /** 只留下越界的那几条（保持原顺序），供调用方 {@code rewind} 用。 */
    public static List<PathBounds> outside(Rect bounds, List<PathBounds> roads) {
        List<PathBounds> out = new ArrayList<>();
        if (bounds == null || roads == null) return out;
        for (PathBounds road : roads) {
            if (road != null && !road.within(bounds)) out.add(road);
        }
        return out;
    }

    /**
     * 能落进 {@link #MAX_CANDIDATE_AREA_BLOCKS} 的最大整数余量（再与 {@code requested} 取小）。
     *
     * <p>解 {@code (coreW + 2m)(coreD + 2m) = areaCap} 的那个非负根，再向下退到整数安全；
     * 根为负/无解（core 自身面积就超 cap）时返回 0 —— 由调用方抬到
     * {@link #MARGIN_FLOOR}（红线：宁可超 cap 也不裁 core）。</p>
     */
    private static int marginToFitCap(long coreW, long coreD, int requested) {
        double b = 2.0 * ((double) coreW + (double) coreD);
        double c = (double) coreW * (double) coreD - (double) MAX_CANDIDATE_AREA_BLOCKS;
        double discriminant = b * b - 16.0 * c;
        int fit = 0;
        if (discriminant > 0 && coreW > 0 && coreD > 0) {
            double root = (-b + Math.sqrt(discriminant)) / 8.0;
            if (root > 0) fit = (int) Math.min(Integer.MAX_VALUE, Math.floor(root));
        }
        while (fit > 0 && candidateArea(coreW, coreD, fit) > MAX_CANDIDATE_AREA_BLOCKS) {
            fit--;
        }
        fit = Math.min(fit, requested);
        return Math.max(MARGIN_FLOOR, fit);
    }

    /** 候选面积（long 饱和，防溢出）。 */
    private static long candidateArea(long coreW, long coreD, long margin) {
        long w = coreW + 2L * margin;
        long d = coreD + 2L * margin;
        if (w <= 0 || d <= 0) return 0;
        if (w > Long.MAX_VALUE / d) return Long.MAX_VALUE;
        return w * d;
    }

    private static int clampMargin(long margin) {
        if (margin < MARGIN_FLOOR) return MARGIN_FLOOR;
        if (margin > MARGIN_CAP) return MARGIN_CAP;
        return (int) margin;
    }

    private static int satAdd(int value, int delta) {
        long r = (long) value + delta;
        return r > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) r;
    }

    private static int satSub(int value, int delta) {
        long r = (long) value - delta;
        return r < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) r;
    }
}
