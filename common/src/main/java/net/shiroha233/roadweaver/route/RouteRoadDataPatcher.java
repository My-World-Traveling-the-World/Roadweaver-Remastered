/* 文件职责：把玩家手绘的路线叠加到管线算出的 RoadData 上 —— 只改走向/高度/桥隧，不动几何。
 *
 * 玩家控制权刻意限定为「中心线走向 + 高度 + 这段是路/桥/隧道」；
 * 宽度、曲线细化、避让结构属于引擎职责，不该让玩家手画。
 */
package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用玩家意图**覆盖**施工管线产出的 {@link RoadData}。
 *
 * <h2>为什么要覆盖</h2>
 * 现有管线（{@code RoadPathCalculator} → {@code PathSpanExtractor}）会：
 * <ul>
 *   <li>把路径高度**吸附回地面** —— 玩家架高的桥会被压平</li>
 *   <li>**自己判定**哪段是桥/隧道 —— 玩家选的类型被忽略</li>
 * </ul>
 * 对手绘路线，这两点都必须以玩家为准（用户明确要求「画什么生成什么」）。
 *
 * <h2>覆盖哪些字段</h2>
 * <pre>
 *   targetY   ← 桥/隧道段严格用玩家值；
 *               ROAD 段取 min(玩家高度, 地形高度) —— 比地形低就挖、高就贴地
 *   spans     ← 玩家类型（BRIDGE / TUNNEL；路不产生 span，交给地形）
 *   segments  ← 保留管线的几何（宽度、平滑、避结构都是成熟逻辑，不动）
 * </pre>
 *
 * <p>⚠️ <b>ROAD 段的规则说明（★ 2026-09-25 更正，原注释写的是"保留管线高度"）</b>：
 * 这里是 {@code Math.min(玩家高度, 地形高度)}，而不是无条件贴地 ——
 * 所以「玩家点比地形低」**会下挖**，「比地形高」才贴地。
 * 想要空中路必须把该段切成 {@link RouteKind#BRIDGE}。
 * 这是用户 2026-09-24 确认过的规则，不是缺陷。</p>
 *
 * <h2>为什么几何不覆盖</h2>
 * 玩家画的是**中心线**，路的宽度、曲线细化、避开村庄建筑这些
 * 都不该让玩家手画 —— 那是引擎该做的。玩家的控制权在
 * 「走向（中心线）」「高度」「这段是路还是桥还是隧道」。
 */
public final class RouteRoadDataPatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRoadDataPatcher.class);

    private RouteRoadDataPatcher() {
    }

    /**
     * 用玩家意图修补 RoadData。
     *
     * @param road    施工管线产出的道路数据
     * @param route   玩家原始路线
     * @param centers 施工分段中心（= {@code road.roadSegmentList()} 的 middlePos 列表）
     * @return 修补后的新实例；无法修补时返回原对象
     */
    public static RoadData patch(RoadData road, PlayerRoute route, List<BlockPos> centers) {
        if (road == null || route == null || route.isEmpty()) {
            return road;
        }
        List<RoadSegmentPlacement> placements = road.roadSegmentList();
        if (placements == null || placements.isEmpty()) {
            return road;
        }

        // 实际几何中心一律从 placement 取，避免调用方传错
        List<BlockPos> actualCenters = centers;
        if (actualCenters == null || actualCenters.size() != placements.size()) {
            actualCenters = new ArrayList<>(placements.size());
            for (RoadSegmentPlacement p : placements) {
                actualCenters.add(p.middlePos());
            }
        }

        // ── ① 平滑玩家路径，供高度映射 ──
        // 注意：这里不需要地形高度（HeightProvider 传 null），
        // 因为路段高度本来就保留管线结果，我们只用它算桥/隧道的高度。
        List<BlockPos> smoothed = RouteSmoother.smooth(route, null);
        if (smoothed.isEmpty()) {
            smoothed = actualCenters;
        }

        // ── ② 逐段映射 ──
        //
        // ★ Codex 审查 D-2（2026-09-25）：长度不匹配时原先**静默 return road**，
        //   既不覆盖也没有任何日志 —— 上层只看到「管线算出的结果」，无法区分
        //   「玩家没画 / 这段就是路」与「映射失败导致玩家意图被静默放弃」。
        //   这里补一条 warn，让这种回退在真机日志里可查。
        //
        //   触发条件：施工分段中心数 ≠ 平滑后的路径点数。正常应相等
        //   （patch 用 smooth(route, null) 重算，与 RouteInjector.plan 同源）。
        //   不等通常意味着「这条连接不是按手绘路线注入的」——
        //   例如已被后处理合并进别的路，此时放弃覆盖是**正确**行为，
        //   但仍应留痕以便排查。
        List<RouteSegmentMapper.MappedSegment> mapped =
                RouteSegmentMapper.map(actualCenters, smoothed, route);
        if (mapped.size() != actualCenters.size()) {
            LOGGER.warn("RoadWeaver player-route patch skipped: center/mapped size mismatch "
                            + "centers={} mapped={} route={} points={} "
                            + "(keeping pipeline result; player intent NOT applied)",
                    actualCenters.size(), mapped.size(), route.id(), route.pointCount());
            return road;
        }

        // ── ③ 覆盖 targetY ──
        List<Integer> originalY = road.targetY();
        List<Integer> patchedY = new ArrayList<>(actualCenters.size());
        for (int i = 0; i < actualCenters.size(); i++) {
            RouteSegmentMapper.MappedSegment m = mapped.get(i);
            if (m.heightFromPlayer()) {
                // 桥/隧道：玩家高度说了算（悬空/入地都是玩家本意）
                patchedY.add(m.targetY());
            } else {
                // ── ROAD 段：★ 低则挖、高则贴地（用户确认的规则）──
                //
                // 玩家反馈：「我端点实际特别设置的是 -62 还是 -61 比平面低
                //           不会下挖 只是落到地表 不会自己往下切方」。
                //
                // 原先 ROAD 一律用管线算出的贴地高度，玩家的 -62 被丢弃 ——
                // 这就是「不下挖」的原因。但也不能完全采信玩家高度：
                // 玩家不可能手动把每个点都对准地表，山坡上的路会变成
                // 台阶或悬空路。
                //
                // 所以取**两者较低的那个**：
                //   玩家比地形低 → 用玩家值（挖下去）
                //   玩家比地形高 → 用地形值（贴地，不筑悬空路）
                //
                // m.targetY() 在这里是玩家画的控制点高度（ROAD 段的平滑点
                // 保留了控制点 Y，因为 patch() 调用 smooth 时 yProvider 传 null）。
                int terrainY = targetAt(originalY, i, actualCenters.get(i).getY());
                patchedY.add(Math.min(m.targetY(), terrainY));
            }
        }

        // ── ④ 坡度收敛（★ 审计 R3-P1-3 / ★ B 锚点保护）──
        //
        // 必须在重建 spans **之前**做：收敛会把断崖变成引桥/引道，
        // 而哪些站属于桥/隧道由 mapped 决定，不受高度变化影响。
        //
        // 为什么需要：RoadDataValidator 的 MAX_HEIGHT_STEP = 1、只容忍 3 次例外，
        // 而每个「路 ↔ 桥/隧道」类型边界天然贡献 1 次高度超限：
        //   · BRIDGE/TUNNEL 段直接用玩家插值高度，与相邻 ROAD 段的
        //     min(玩家, 地形) 之间没有任何过渡
        //   · 桥段内部的坡度也会逐站超限（玩家很自然会「A 点放岸边、
        //     B 点抬高 16 格、相距 20 格」⇒ 约 1.6 格/站）
        // ⇒ 两条桥（4 个边界）就会让整条路线的玩家意图被**静默丢弃**，
        //   只剩一行 warn。实测最惨的情况是「一条桥都没建，全是贴地路」。
        //
        // ★ B（2026-09-25 修）：桥/隧道段**逐站**当锚点，绝不下调。
        //
        // 原实现只把「中段」当锚点、且前向 pass 的 `delta > MAX_STEP` 分支
        // 是 `out.set(i, prev + MAX_STEP)` —— **把当前站往下压**，与注释
        // 「宁可把引道抬高，也不降低桥面」正好相反。后果：
        //   · 长桥能活（中段保住玩家值，两端变引桥）
        //   · **短桥（1~3 站）被直接压平** —— 真机反馈「我手动设的 -55
        //     比地面 -60 高，为什么不做空中路」就是这个：2~3 站的桥
        //     被压成 -58/-59，看不出是桥。
        //
        // 现在把 mapped 里 heightFromPlayer() 的每一站都标成锚点，
        // 收敛时只允许**抬高两侧引道/引桥**，锚点值逐格保持。
        boolean[] anchor = new boolean[actualCenters.size()];
        for (int i = 0; i < anchor.length && i < mapped.size(); i++) {
            anchor[i] = mapped.get(i).heightFromPlayer();
        }
        List<Integer> convergedY = convergeHeightProfile(patchedY, actualCenters.size(), anchor);

        // ── ⑤ 重建 spans：桥/隧道按玩家类型；路不产生 span ──
        List<RoadSpan> patchedSpans = buildPlayerSpans(actualCenters, mapped, road);

        return new RoadData(
                road.width(),
                road.roadType(),
                road.materials(),
                road.slabMaterials(),
                road.roadSegmentList(),
                patchedSpans,
                convergedY,
                road.ownerA2dKey(),
                road.ownerB2dKey()
        );
    }

    /**
     * 把高度剖面收敛成「每站 |ΔY| ≤ {@value #MAX_STEP}」，**锚点站不被下调**。
     *
     * <h2>算法</h2>
     * 反复做「前向拉平 + 后向拉平」，直到不再有超限步进或达到迭代上限。
     * 这与 {@code RoadDataValidator.repairFinalHeightSteps} 用的是同一套思路，
     * 区别是本方法在**patch 之后立刻**执行 —— 于是校验根本不会被触发，
     * 而不是「先生成非法数据再靠 repair 兜底」。
     *
     * <h2>★ B：锚点保护（2026-09-25 修）</h2>
     * 旧实现有一处与其注释**相反**的行为：前向 pass 遇到「当前站比前一站高太多」
     * 时执行 {@code out.set(i, prev + MAX_STEP)} —— 这是**把当前站往下压**。
     * 注释写的是「宁可把引道抬高，也不降低桥面」，代码做的却是压桥面。后果：
     * <pre>
     *   桥面 -55、两侧地面 -60、桥占 3 站
     *   期望: [-60, -55, -55, -55, -60]
     *   实际: [-60, -59, -58, -59, -60]   ← 桥面被压低 4 格，看不出是桥
     * </pre>
     * 长桥侥幸能活（中段保住了），**短桥（1~3 站）被整个压平** ——
     * 这正是玩家反馈「我设的比地面高，为什么不做空中路」的原因。
     *
     * <p>现在引入 {@code anchor}：{@code true} 的站在收敛过程中**只许被抬高、
     * 不许被降低**（桥面/隧道底是玩家明确指定的）。遇到「锚点比邻居高太多」
     * 时改为**抬高邻居**（引桥），而不是压低锚点。这样：</p>
     * <ul>
     *   <li>短桥：两侧引桥被抬起来 → 桥面保住玩家值 ✅</li>
     *   <li>长桥：行为与旧实现一致（中段本来就是锚点）✅</li>
     *   <li>无法收敛时：仍在迭代上限后返回，由调用方的三级回退兜底 ✅</li>
     * </ul>
     *
     * @param profile 逐站高度（就地不改，返回新列表）
     * @param n       站点数（用于长度校验）
     * @param anchor  逐站锚点标记（{@code null} = 无锚点，等同旧行为）
     * @return 收敛后的剖面；输入非法时原样返回副本
     */
    private static List<Integer> convergeHeightProfile(List<Integer> profile, int n,
                                                       boolean[] anchor) {
        List<Integer> out = new ArrayList<>(profile);
        if (n <= 1 || out.size() <= 1) {
            return out;
        }
        // ★ 关键：**没有锚点时不改变任何行为**。
        //
        // 纯「路」的手绘路线（不含桥/隧道）没有锚点，此时必须与旧实现
        // **逐格一致** —— 否则会悄悄改变已部署版本的道路结果（红线）。
        // 新规则只在「确实有桥/隧道锚点」时生效，影响面仅限玩家明确
        // 指定了高度的那些路线。
        boolean hasAnchor = false;
        for (int i = 0; i < out.size(); i++) {
            if (isAnchor(anchor, i)) {
                hasAnchor = true;
                break;
            }
        }

        // 迭代上限：最坏情况是「一端比另一端高很多」，每轮只能传播 1 格。
        // 站点数有限，所以「站点数」轮足以收敛（每轮至少把一个超限边压平）。
        int maxRounds = Math.min(out.size() + 1, 4096);
        for (int round = 0; round < maxRounds; round++) {
            boolean changed = false;
            // 前向：让每个站不高于「前一站 + MAX_STEP」
            for (int i = 1; i < out.size(); i++) {
                Integer prev = out.get(i - 1);
                Integer cur = out.get(i);
                if (prev == null || cur == null) {
                    continue;
                }
                int delta = cur - prev;
                if (delta > MAX_STEP) {
                    // 当前站比前一站高太多。
                    if (hasAnchor && isAnchor(anchor, i)) {
                        // 当前站是锚点（桥面/隧道底）→ 抬高**前一站**（引桥），
                        // 绝不压低锚点。
                        out.set(i - 1, cur - MAX_STEP);
                    } else {
                        out.set(i, prev + MAX_STEP);
                    }
                    changed = true;
                } else if (delta < -MAX_STEP) {
                    // 下降太猛。
                    if (hasAnchor && isAnchor(anchor, i)) {
                        // 当前站是锚点（隧道底比地面低）→ 压低**前一站**逼近它，
                        // 不动锚点。
                        out.set(i - 1, cur + MAX_STEP);
                    } else if (hasAnchor) {
                        // ★ B 的核心修复：抬高**当前站**（引道）而不是把前一站
                        // 抬到离谱的高度。
                        //
                        // 旧实现抬高前一站，当锚点（如桥面 -55）把前一站钉住时，
                        // 会出现「前一站已被锚点约束、当前站又离得太远」的死结：
                        // 前向压 index i、后向又改回 index i-1，**两个 pass 来回拉锯
                        // 直到迭代上限**，返回一个仍未收敛的剖面 → 校验失败 →
                        // 整条路线退回管线结果（玩家看到"桥全没了"）。
                        // 抬高当前站可一次性满足 |Δ| ≤ 1，且与锚点无冲突。
                        out.set(i, prev - MAX_STEP);
                    } else {
                        // 无锚点：保持旧行为（逐格一致）
                        out.set(i - 1, cur + MAX_STEP);
                    }
                    changed = true;
                }
            }
            // 后向：让每个站不低于「后一站 - MAX_STEP」
            for (int i = out.size() - 2; i >= 0; i--) {
                Integer next = out.get(i + 1);
                Integer cur = out.get(i);
                if (next == null || cur == null) {
                    continue;
                }
                int delta = next - cur;
                if (delta > MAX_STEP) {
                    // 后一站比当前站高太多。
                    if (hasAnchor && isAnchor(anchor, i)) {
                        // 当前站是锚点 → 抬高后一站（引桥），不动锚点
                        out.set(i + 1, cur + MAX_STEP);
                    } else {
                        out.set(i, next - MAX_STEP);
                    }
                    changed = true;
                } else if (delta < -MAX_STEP) {
                    if (hasAnchor && isAnchor(anchor, i + 1)) {
                        // 后一站是锚点（隧道底）→ 压低当前站逼近它
                        out.set(i, next + MAX_STEP);
                    } else {
                        out.set(i + 1, cur - MAX_STEP);
                    }
                    changed = true;
                }
            }
            if (!changed) {
                return out;
            }
        }
        return out;
    }

    /** 安全读锚点标记（越界或 null 一律视为非锚点）。 */
    private static boolean isAnchor(boolean[] anchor, int index) {
        return anchor != null && index >= 0 && index < anchor.length && anchor[index];
    }

    /** 每站允许的最大高度变化（与 {@code RoadDataValidator.MAX_HEIGHT_STEP} 一致）。 */
    private static final int MAX_STEP = 1;

    /**
     * 把 {@code patched} 中「以 {@code index} 为中心的一小段」恢复成管线值
     * （★ 审计 R3-P1-3 的第 ② 级回退）。
     *
     * <h2>为什么要按段而不是整条</h2>
     * 覆盖是「全有或全无」的：一处局部错误（某个桥抬太高）会让整条路线的
     * 玩家意图被丢弃 —— 玩家看到的是「所有桥都没了」。
     * 按段回退保证只有**真正有问题的转折点**回到算法结果，
     * 其余桥/隧道/高度一律保留玩家设定。
     *
     * <h2>回退范围</h2>
     * 以 {@code index} 为中心向两侧各扩 {@value #REVERT_HALF_WINDOW} 站。
     * 取一小段而不是单站，是因为超限步进通常成对出现（上坡的起点与终点），
     * 只改一站会把断崖挪个位置而不是消掉它。
     *
     * @param patched  玩家覆盖后的 RoadData
     * @param pipeline 管线原始结果（回退目标）
     * @param index    校验报出的失败站点下标
     * @return 回退后的新实例；无法构造时返回 null（调用方退回整条）
     */
    public static RoadData revertRangeToPipeline(RoadData patched, RoadData pipeline, int index) {
        if (patched == null || pipeline == null) {
            return null;
        }
        List<Integer> patchedY = patched.targetY();
        List<Integer> pipelineY = pipeline.targetY();
        if (patchedY == null || pipelineY == null || patchedY.isEmpty()) {
            return null;
        }
        // 管线结果与 patch 结果必须同长，否则下标对不上（不同批次的数据）
        if (patchedY.size() != pipelineY.size()) {
            return null;
        }
        if (index < 0 || index >= patchedY.size()) {
            return null;
        }

        List<Integer> merged = new ArrayList<>(patchedY);
        int from = Math.max(0, index - REVERT_HALF_WINDOW);
        int to = Math.min(merged.size() - 1, index + REVERT_HALF_WINDOW);
        for (int i = from; i <= to; i++) {
            Integer v = pipelineY.get(i);
            if (v != null) {
                merged.set(i, v);
            }
        }
        return new RoadData(
                patched.width(),
                patched.roadType(),
                patched.materials(),
                patched.slabMaterials(),
                patched.roadSegmentList(),
                patched.spans(),
                merged,
                patched.ownerA2dKey(),
                patched.ownerB2dKey()
        );
    }

    /** 按段回退时向失败站点两侧扩展的站数。 */
    private static final int REVERT_HALF_WINDOW = 3;

    /** 安全取 targetY（长度不足时用 fallback）。 */
    private static int targetAt(List<Integer> list, int index, int fallback) {
        if (list == null || index < 0 || index >= list.size()) {
            return fallback;
        }
        Integer v = list.get(index);
        return v == null ? fallback : v;
    }

    /**
     * 按玩家类型重建跨度列表。
     *
     * <h2>★ 核心规则：玩家 span 优先，玩家未覆盖的区间保留管线 span</h2>
     * 第一版只保留了 4 种「安全相关」的管线 span（PROTECTED_GAP / JUNCTION_ANCHOR /
     * TUNNEL_JUNCTION / ROAD_CROSSING），**丢弃了 BRIDGE / TUNNEL / NETWORK_REUSE**。
     * 这会造成自相矛盾（审计 P1-1）：
     * <pre>
     *   玩家把跨河段标成 ROAD（让地形决定）
     *     → targetY 保留管线值（那是【按"这里有桥"算出来】的）
     *     → spans 里却没有桥了
     *     → RoadDataValidator 判 invalid → chunkgen 静默丢弃整条路
     * </pre>
     * 正确做法是把管线 span **全部保留**（除非该区间已被玩家 span 覆盖），
     * 因为 targetY 就是从它们算出来的 —— 丢了 span 就丢了自洽性。
     *
     * <h2>单点区间也要出 span（P1-2）</h2>
     * 原判据 {@code end > start} 会让「只有一个 placement 的桥/隧道」完全不产生 span，
     * 但它的 targetY 已被抬高 → 在半空铺普通路面（悬空路）。
     * 改为 {@code end >= start}。
     */
    private static List<RoadSpan> buildPlayerSpans(List<BlockPos> centers,
                                                   List<RouteSegmentMapper.MappedSegment> mapped,
                                                   RoadData road) {
        List<RoadSpan> out = new ArrayList<>();

        int i = 0;
        while (i < mapped.size()) {
            RouteKind kind = mapped.get(i).kind();
            int start = i;
            while (i + 1 < mapped.size() && mapped.get(i + 1).kind() == kind) {
                i++;
            }
            int end = i;

            SpanType type = switch (kind) {
                case BRIDGE -> SpanType.BRIDGE;
                case TUNNEL -> SpanType.TUNNEL;
                case ROAD -> null;      // 路不产生玩家 span
            };
            // ★ end >= start：单点也要出 span，否则 targetY 抬高却没有对应跨度
            if (type != null) {
                out.add(new RoadSpan(centers.get(start), centers.get(end), type));
            }
            i++;
        }

        // ── 保留管线 span（玩家未覆盖的）──
        //
        // 不再按类型白名单筛选：targetY 是这些 span 算出来的，
        // 丢掉任何一个都会让高度与几何不自洽（审计 P1-1）。
        // 唯一的例外是「已被玩家 span 覆盖」的区间 —— 那种重复保留会触发
        // RoadDataValidator 的 overlapping-physical-spans。
        if (road.spans() != null) {
            // 建索引：管线 span 的端点也是 segment 中心，用下标比较最精确。
            // 不能用坐标算术当序数（路径反向/斜向时不单调，会误判重叠）。
            Map<BlockPos, Integer> indexOf = new HashMap<>();
            for (int k = 0; k < centers.size(); k++) {
                indexOf.putIfAbsent(centers.get(k), k);
            }
            List<int[]> playerRanges = playerSpanRanges(out, indexOf);

            for (RoadSpan s : road.spans()) {
                if (s == null || s.type() == null) {
                    continue;
                }
                if (coveredByPlayerSpan(s, indexOf, playerRanges)) {
                    continue;
                }
                out.add(s);
            }
        }
        return out;
    }

    /** 把玩家 span 转成 {startIndex, endIndex} 区间列表（端点不在表里的跳过）。 */
    private static List<int[]> playerSpanRanges(List<RoadSpan> playerSpans,
                                                Map<BlockPos, Integer> indexOf) {
        List<int[]> out = new ArrayList<>();
        for (RoadSpan s : playerSpans) {
            if (s.type() != SpanType.BRIDGE && s.type() != SpanType.TUNNEL) {
                continue;
            }
            Integer a = indexOf.get(s.start());
            Integer b = indexOf.get(s.end());
            if (a == null || b == null) {
                continue;
            }
            out.add(new int[]{Math.min(a, b), Math.max(a, b)});
        }
        return out;
    }

    /**
     * 该管线 span 是否已被某个玩家 span 覆盖。
     *
     * <p>用**下标区间重叠**判断，而不是坐标比较 —— 两者都源自同一份 segment
     * 中心列表，下标是唯一的精确顺序。端点查不到时退回起终点全等判断
     * （保守：宁可保留也不误删）。</p>
     */
    private static boolean coveredByPlayerSpan(RoadSpan candidate,
                                               Map<BlockPos, Integer> indexOf,
                                               List<int[]> playerRanges) {
        Integer a = indexOf.get(candidate.start());
        Integer b = indexOf.get(candidate.end());
        if (a == null || b == null) {
            for (int[] r : playerRanges) {
                // 无法定位就只认「覆盖了整条路径」这种极端情况，避免误删
                if (r[0] == 0 && r[1] == Integer.MAX_VALUE) {
                    return true;
                }
            }
            return false;
        }
        int c0 = Math.min(a, b);
        int c1 = Math.max(a, b);
        for (int[] r : playerRanges) {
            // 闭区间有重叠即算覆盖
            if (r[0] <= c1 && c0 <= r[1]) {
                return true;
            }
        }
        return false;
    }
}
