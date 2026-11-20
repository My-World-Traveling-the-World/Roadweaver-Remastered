/* 文件职责：把互相覆盖的道路归一化成「物理归属唯一」——重复段只留一条路的方块。 */
package net.shiroha233.roadweaver.worldgen.road;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物理归属归一化：同一段世界方块只能属于一条路。
 *
 * <h2>为什么需要</h2>
 * <p>两条路可能规划出重叠的走廊。如果两边都往同一批方块写字，后写的赢，
 * 结果是路面材质在中途突然换掉、桥梁与路面互相覆盖、剖面高度来回跳。
 * 本类在<b>编译世界生成计划之前</b>把这些重叠归零：确定谁保留、谁让出，
 * 并把让出方的 span 改成 {@code SpanType.NETWORK_REUSE}（表示「这里走别人的路」）。</p>
 *
 * <h2>拆分来源</h2>
 * <p>原先是 {@code RoadChunkPlanCompiler} 里的一部分。那个文件一度 1048 行，
 * 超过本仓「一个文件不得超过千行」的硬约束；而且「归一化重叠」与
 * 「编译区块计划」是两件独立的事，混在一起时改一边要读另一边。
 * 按功能拆开后，本类只关心道路与道路之间的关系，完全不知道区块的存在。</p>
 *
 * <h2>关键不变量</h2>
 * <ul>
 *   <li>让出方必须有 {@code verifySurrenderedReuse} 校验 —— 参考
 *       {@code NETWORK_REUSE} 有 fail-safe，而 {@code PROTECTED_GAP} 没有，
 *       历史上那是多根因汇点（见 2026-11-07 七路分析）。</li>
 *   <li>只让出<b>方向兼容</b>（{@code physicalDirectionCompatible}）且
 *       <b>跨度类型兼容</b>（{@code physicalSpanKindCompatible}）的段，
 *       否则桥与隧道互相覆盖会出结构错位。</li>
 * </ul>
 *
 * @see RoadChunkPlanCompiler
 */
final class RoadPhysicalReuseNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private RoadPhysicalReuseNormalizer() {}

    // ── 物理复用的判据常量（原在 RoadChunkPlanCompiler，移到这里与使用点同文件）──

    /** 一段连续复用至少要多少站才算「真的重叠」，小于此值视为偶然擦碰。 */
    private static final int PHYSICAL_REUSE_MIN_RUN = 4;
    /** 两条路在对应站上的中心最大水平距离；超过就不是同一条走廊。 */
    private static final int PHYSICAL_REUSE_MAX_CENTER_DISTANCE = 5;
    /** 走廊级重叠（而非逐站匹配）的最小连续站数。 */
    private static final int PHYSICAL_REUSE_CORRIDOR_MIN_RUN = 8;
    /** 让出方与保留方的目标高度最大差；超过说明是上下层不同的路，不能互相覆盖。 */
    private static final int PHYSICAL_REUSE_MAX_HEIGHT_DELTA = 1;
    /** 保留原匹配上界；桶宽取同样大小，每站只需查至多 3×3 个桶。 */
    private static final int MAX_OWNER_DISTANCE = 64;
    private static final int OWNER_CELL_SHIFT = 6;
    /** 沿主路前进时索引最多跳几步；跳太多说明匹配到的是另一段。 */
    private static final int PHYSICAL_REUSE_MAX_PRIMARY_INDEX_STEP = 8;

    static List<RoadData> normalizePhysicalOverlaps(List<RoadData> roads) {
        // ★ 2026-10-15: 单条路也要走一遍 —— 原先 size() < 2 直接返回，会让「这条
        // 路让出的那一段，编译集合里根本没有主人」这种最坏情况被跳过（孤儿让出
        // 恰恰常出现在主人已被删除、只剩它自己触到该区块的时候）。
        if (roads == null || roads.isEmpty()) return List.of();
        // Query order changes after a road replacement and is not a durable
        // ownership contract. Use the canonical road fingerprint so every
        // chunk compile selects the same physical owner.
        List<RoadData> ordered = new ArrayList<>(roads);
        ordered.sort(Comparator.comparingLong(RoadFingerprint::compute));
        List<RoadData> normalized = new ArrayList<>(roads.size());
        Preparation preparation = new Preparation();
        for (RoadData secondary : ordered) {
            if (secondary == null || secondary.roadSegmentList() == null
                    || secondary.roadSegmentList().size() < PHYSICAL_REUSE_MIN_RUN) {
                normalized.add(secondary);
                continue;
            }
            boolean[] reused = new boolean[secondary.roadSegmentList().size()];
            // 密集/小批次直接枚举，避免为原本几乎全中的集合建候选和检查未来道路号。
            if (preparation.direct(ordered)) {
                for (RoadData primary : normalized) markPhysicalReuse(primary, secondary, reused, false, preparation);
            } else {
                for (int candidate : preparation.candidates(ordered, secondary)) {
                    if (candidate >= normalized.size()) break;
                    markPhysicalReuse(normalized.get(candidate), secondary, reused, false, preparation);
                }
            }
            RoadData body = hasTrue(reused) ? withPhysicalReuseSpans(secondary, reused) : secondary;
            normalized.add(verifySurrenderedReuse(body, secondary, ordered, reused, preparation));
        }
        return List.copyOf(normalized);
    }

    /** 让出共用段的总量仅作节流诊断，实际归属仍由逐站掩码决定。 */
    static final java.util.concurrent.atomic.AtomicLong RECLAIMED_REUSE_STATIONS =
            new java.util.concurrent.atomic.AtomicLong();
    static volatile long lastReclaimLogNanos;

    /**
     * ★ 2026-10-15：**让出必须有人接** —— 不可兑现的 NETWORK_REUSE 由本路自己铺。
     *
     * <h2>缺陷</h2>
     * NETWORK_REUSE 是规划期写在**后一条路**上的元数据，含义是「这段由主路铺」，
     * 但它匹配的是主路的**规划期中心线快照**
     * （{@code PlannedPathCache.plannedReuseSpans}），而主路随后可能：
     * <ul>
     *   <li>在 abort / roadside 失败时被 {@code RoadShardStorage.deleteRoad} 删掉
     *       （{@code Road.java} 的清理路径），此后没有任何代码回头检查依赖它的路；</li>
     *   <li>在四次几何处理与 {@code trimPathNearStructure} 之后被裁短，末端比
     *       让出的那一段短几十格。</li>
     * </ul>
     * 两种情况下这段路**两头都不铺**：本路见到自己的 NETWORK_REUSE 站点就
     * {@code continue}（{@code PathFeature} 的所有权契约），主路却压根没覆盖到。
     * 玩家看到的就是「地图上有蓝线、世界里断一截」。
     *
     * <h2>修法（fail-safe：多铺一段，好过留个洞）</h2>
     * 一次编译里，对每个让出站点问一句：**本次编译集合里真有别的路铺到这儿吗？**
     * 有 ⇒ 保持让出（原有的单次铺设契约不变）；没有 ⇒ 撤销让出，本路自己铺。
     * 判据复用完全相同的匹配几何（Chebyshev 距离、高差、方向、区间长度），
     * 只是允许让出站点参与匹配 —— 原来的匹配恰恰把它们排除在外，于是**在最需要
     * 验证的地方是瞎的**。
     *
     * <p>代价：当主人路不在本次编译集合里（区块级查询）时可能重复铺一次同一段
     * 路面；两个方向都比「整段缺失」轻。确定性与世界兼容性不受影响：只改内存里
     * 的编译结果，落盘的 spans/placements 与指纹都不动。</p>
     */
    static RoadData verifySurrenderedReuse(RoadData body, RoadData original,
                                                   List<RoadData> all, boolean[] reused) {
        return verifySurrenderedReuse(body, original, all, reused, new Preparation());
    }

    private static RoadData verifySurrenderedReuse(RoadData body, RoadData original,
            List<RoadData> all, boolean[] reused, Preparation preparation) {
        if (original.spans() == null || original.spans().isEmpty()) return body;
        boolean[] surrendered = RoadChunkPlanCompiler.buildSpanMask(
                original.roadSegmentList(), original.spans(), SpanType.NETWORK_REUSE);
        if (surrendered == null || !hasTrue(surrendered)) return body;
        boolean[] claimed = new boolean[surrendered.length];
        if (preparation.direct(all)) {
            for (RoadData other : all) {
                if (other == null || other == original) continue;
                markPhysicalReuse(other, original, claimed, true, preparation);
            }
        } else {
            for (int candidate : preparation.candidates(all, original)) {
                RoadData other = all.get(candidate);
                if (other == null || other == original) continue;
                markPhysicalReuse(other, original, claimed, true, preparation);
            }
        }
        boolean[] keep = new boolean[surrendered.length];
        int dropped = 0;
        for (int i = 0; i < keep.length; i++) {
            boolean owned = reused != null && i < reused.length && reused[i];
            keep[i] = owned || (surrendered[i] && claimed[i]);
            if (surrendered[i] && !keep[i]) dropped++;
        }
        if (dropped == 0) return body;
        reportReclaimedReuse(dropped);
        return rebuildReuseSpans(body, keep);
    }

    static void reportReclaimedReuse(int dropped) {
        long total = RECLAIMED_REUSE_STATIONS.addAndGet(dropped);
        long now = System.nanoTime();
        if (now - lastReclaimLogNanos < 10_000_000_000L) return;
        lastReclaimLogNanos = now;
        LOGGER.info("【复用站点没有任何主路负责铺设，已收回并由当前道路施工】 " + "RoadWeaver reclaimed {} station(s) of NETWORK_REUSE that nobody paves "
                        + "(total {} this session); the marking road lays them itself instead of a gap.",
                dropped, total);
    }

    /** 从保留掩码重建NETWORK_REUSE，其他跨度保留，避免归一时丢桥隧信息。 */
    static RoadData rebuildReuseSpans(RoadData road, boolean[] keep) {
        List<RoadSpan> spans = new ArrayList<>();
        for (RoadSpan span : road.spans()) {
            if (span != null && span.type() != SpanType.NETWORK_REUSE) spans.add(span);
        }
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        int start = -1;
        for (int i = 0; i <= keep.length; i++) {
            boolean value = i < keep.length && keep[i];
            if (value && start < 0) start = i;
            if (!value && start >= 0) {
                BlockPos from = segments.get(start).middlePos();
                BlockPos to = segments.get(i - 1).middlePos();
                if (from != null && to != null) {
                    spans.add(new RoadSpan(from, to, SpanType.NETWORK_REUSE));
                }
                start = -1;
            }
        }
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                segments, spans, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    static boolean hasTrue(boolean[] values) {
        if (values == null) return false;
        for (boolean value : values) if (value) return true;
        return false;
    }

    static void markPhysicalReuse(RoadData primary, RoadData secondary, boolean[] reused) {
        markPhysicalReuse(primary, secondary, reused, false);
    }

    /**
     * ★ 2026-10-15：同一个匹配过程，两种用途。
     *
     * <p>{@code matchReuseStations == false}（原有语义）：次路的 NETWORK_REUSE 站点
     * 视作「已让出」，不参与匹配 —— 它已经不属于自己，不能被别的路再认领一次。</p>
     *
     * <p>{@code matchReuseStations == true}（新增）：**反过来问「这段让出去的
     * 路，真的有人铺吗」**。让出是规划期写下的元数据（
     * {@code PlannedPathCache.plannedReuseSpans} 匹配的是主路的**规划期中心线快照**），
     * 而主路可能随后被删（{@code Road.java} 的 abort 路径会
     * {@code RoadShardStorage.deleteRoad}）或在四次几何处理里被裁短。那样这段路
     * **两头都不铺**：次路在这里跳过（{@code PathFeature} 见 NETWORK_REUSE 即
     * continue），主路却根本没有覆盖到 —— 地图上有蓝线，世界里是空的。</p>
     */
    static void markPhysicalReuse(RoadData primary, RoadData secondary, boolean[] reused,
                                          boolean matchReuseStations) {
        markPhysicalReuse(primary, secondary, reused, matchReuseStations, new Preparation());
    }

    private static void markPhysicalReuse(RoadData primary, RoadData secondary, boolean[] reused,
            boolean matchReuseStations, Preparation preparation) {
        if (primary == null || secondary == null || reused == null
                || primary.roadSegmentList() == null || primary.roadSegmentList().size() < 2
                || primary.roadType() != secondary.roadType()) return;
        // A primary NETWORK_REUSE span still describes the physical corridor
        // owned by an earlier road and may be used as the canonical owner.
        // A secondary NETWORK_REUSE span is already non-owning and must not be
        // claimed again by another later route.
        var owners = preparation.owner(primary);
        if (owners.buckets.isEmpty()) return;
        SecondaryPrepared prepared = preparation.secondary(secondary, !matchReuseStations);
        boolean[] secondaryBlocked = prepared.blocked();
        List<RoadSegmentPlacement> sec = secondary.roadSegmentList();
        SpanKind[] secondaryKinds = prepared.kinds();
        int[] matches = new int[sec.size()];
        boolean[] corridorMatches = new boolean[sec.size()];
        Arrays.fill(matches, -1);
        for (int i = 0; i < sec.size(); i++) {
            if (secondaryBlocked[i]) continue;
            RoadSegmentPlacement candidate = sec.get(i);
            if (candidate == null || candidate.middlePos() == null) continue;
            ReuseMatch match = closestReuseOwner(owners, secondary, i, secondaryKinds[i], candidate);
            matches[i] = match.index;
            corridorMatches[i] = match.corridor;
        }

        int runStart = -1;
        int previousPrimary = -1;
        boolean corridorRun = false;
        for (int i = 0; i <= matches.length; i++) {
            int current = i < matches.length ? matches[i] : -1;
            boolean contiguous = current >= 0 && (runStart < 0
                    || Math.abs(current - previousPrimary) <= PHYSICAL_REUSE_MAX_PRIMARY_INDEX_STEP);
            if (contiguous) {
                if (runStart < 0) runStart = i;
                corridorRun |= corridorMatches[i];
                previousPrimary = current;
                continue;
            }
            int requiredRun = corridorRun
                    ? PHYSICAL_REUSE_CORRIDOR_MIN_RUN : PHYSICAL_REUSE_MIN_RUN;
            if (runStart >= 0 && i - runStart >= requiredRun) {
                for (int index = runStart; index < i; index++) reused[index] = true;
            }
            runStart = current >= 0 ? i : -1;
            corridorRun = current >= 0 && corridorMatches[i];
            previousPrimary = current;
        }
    }

    /** 只活在一次归一化调用中；归属依赖本次道路集合，不能跨编译缓存。 */
    static final class Preparation {
        private final Map<RoadData, OwnerStationIndex> owners = new java.util.IdentityHashMap<>();
        private final Map<RoadData, SecondaryPrepared[]> secondaries = new java.util.IdentityHashMap<>();
        private RoadReuseCandidates candidates;
        boolean direct(List<RoadData> roads) {
            if (roads.size() < 16) return true;
            if (candidates == null) candidates = new RoadReuseCandidates(roads, MAX_OWNER_DISTANCE);
            return candidates.usesDirectEnumeration();
        }
        int[] candidates(List<RoadData> roads, RoadData road) {
            // 索引只依赖中心线；本轮归一化仅改spans，原/归一道路共用道路号但准备状态仍按实例隔离。
            if (candidates == null) candidates = new RoadReuseCandidates(roads, MAX_OWNER_DISTANCE);
            return candidates.nearby(road);
        }
        OwnerStationIndex owner(RoadData road) {
            return owners.computeIfAbsent(road, RoadPhysicalReuseNormalizer::indexPrimaryStations);
        }
        SecondaryPrepared secondary(RoadData road, boolean blockReuse) {
            SecondaryPrepared[] modes = secondaries.computeIfAbsent(road, ignored -> new SecondaryPrepared[2]);
            int slot = blockReuse ? 1 : 0;
            if (modes[slot] == null) modes[slot] = new SecondaryPrepared(
                    physicalReuseBlocked(road, blockReuse), spanKinds(road, blockReuse));
            return modes[slot];
        }
    }
    private record SecondaryPrepared(boolean[] blocked, SpanKind[] kinds) {}

    private static OwnerStationIndex indexPrimaryStations(RoadData road) {
        List<RoadSegmentPlacement> stations = road.roadSegmentList();
        boolean[] blocked = physicalReuseBlocked(road, false);
        var buckets = new Long2ObjectOpenHashMap<IntArrayList>();
        for (int i = 0; i < stations.size(); i++) {
            RoadSegmentPlacement station = stations.get(i);
            if (blocked[i] || station == null || station.middlePos() == null) continue;
            BlockPos center = station.middlePos();
            buckets.computeIfAbsent(ownerCellKey(center.getX() >> OWNER_CELL_SHIFT,
                    center.getZ() >> OWNER_CELL_SHIFT), key -> new IntArrayList()).add(i);
        }
        return new OwnerStationIndex(road, stations, spanKinds(road), buckets);
    }

    private static ReuseMatch closestReuseOwner(OwnerStationIndex primary, RoadData secondary,
                                                int i, SpanKind secondaryKind,
                                                RoadSegmentPlacement candidate) {
        BlockPos center = candidate.middlePos();
        int x = center.getX();
        int z = center.getZ();
        // 对负坐标也向下取整；先用 long 算窗口两端，避免偏移在边界回绕。
        int minX = (int) (((long) x - MAX_OWNER_DISTANCE) >> OWNER_CELL_SHIFT);
        int maxX = (int) (((long) x + MAX_OWNER_DISTANCE) >> OWNER_CELL_SHIFT);
        int minZ = (int) (((long) z - MAX_OWNER_DISTANCE) >> OWNER_CELL_SHIFT);
        int maxZ = (int) (((long) z + MAX_OWNER_DISTANCE) >> OWNER_CELL_SHIFT);
        int best = -1;
        long bestDistance = MAX_OWNER_DISTANCE + 1;
        boolean bestCorridor = false;
        int targetY = RoadChunkPlanCompiler.targetAt(secondary.targetY(), i, center.getY());
        for (int bz = minZ; bz <= maxZ; bz++) {
            for (int bx = minX; bx <= maxX; bx++) {
                IntArrayList bucket = primary.buckets.get(ownerCellKey(bx, bz));
                if (bucket == null) continue;
                for (int k = 0; k < bucket.size(); k++) {
                    int j = bucket.getInt(k);
                    RoadSegmentPlacement owner = primary.stations.get(j);
                    BlockPos ownerCenter = owner.middlePos();
                    long distance = Math.max(Math.abs((long) x - ownerCenter.getX()),
                            Math.abs((long) z - ownerCenter.getZ()));
                    // 桶的遍历顺序不同于原全扫；等距时显式保留主路站序最早者。
                    if (distance > MAX_OWNER_DISTANCE || distance > bestDistance
                            || (distance == bestDistance && j >= best)) continue;
                    SpanKind primaryKind = primary.kinds[j];
                    if (!physicalSpanKindCompatible(primaryKind, secondaryKind)
                            || !physicalDirectionCompatible(secondary.roadSegmentList(), i, primary.stations, j)) continue;
                    boolean sameTunnel = primaryKind == SpanKind.TUNNEL && secondaryKind == SpanKind.TUNNEL;
                    boolean exactFootprint = footprintsIntersect(owner, candidate);
                    // 方向相同但足迹不相交的隧道不是同一条；地面路另需满足高差和中心距离。
                    if (sameTunnel ? !exactFootprint
                            : ((!exactFootprint && distance > PHYSICAL_REUSE_MAX_CENTER_DISTANCE)
                            || Math.abs(targetY - RoadChunkPlanCompiler.targetAt(primary.road.targetY(), j,
                            ownerCenter.getY())) > PHYSICAL_REUSE_MAX_HEIGHT_DELTA)) continue;
                    best = j;
                    bestDistance = distance;
                    bestCorridor = !sameTunnel && !exactFootprint;
                }
            }
        }
        return best < 0 ? ReuseMatch.NONE : new ReuseMatch(best, bestCorridor);
    }

    private static long ownerCellKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private record OwnerStationIndex(RoadData road, List<RoadSegmentPlacement> stations,
                                     SpanKind[] kinds, Long2ObjectOpenHashMap<IntArrayList> buckets) {
    }

    private record ReuseMatch(int index, boolean corridor) {
        private static final ReuseMatch NONE = new ReuseMatch(-1, false);
    }

    static boolean physicalDirectionCompatible(List<RoadSegmentPlacement> first, int firstIndex,
                                                        List<RoadSegmentPlacement> second, int secondIndex) {
        double[] a = localDirection(first, firstIndex);
        double[] b = localDirection(second, secondIndex);
        return a == null || b == null || Math.abs(a[0] * b[0] + a[1] * b[1]) >= 0.75D;
    }

    static double[] localDirection(List<RoadSegmentPlacement> segments, int index) {
        if (segments == null || segments.size() < 2) return null;
        BlockPos first = segments.get(Math.max(0, index - 1)).middlePos();
        BlockPos last = segments.get(Math.min(segments.size() - 1, index + 1)).middlePos();
        if (first == null || last == null) return null;
        double dx = last.getX() - first.getX();
        double dz = last.getZ() - first.getZ();
        double length = Math.hypot(dx, dz);
        return length < 1.0D ? null : new double[]{dx / length, dz / length};
    }

    static boolean[] physicalReuseBlocked(RoadData road, boolean blockNetworkReuse) {
        boolean[] blocked = new boolean[road.roadSegmentList().size()];
        // 无跨度的普通道路无需为查不到的端点建索引。
        if (road.spans() == null || road.spans().isEmpty()) return blocked;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            boolean blocksReuse = switch (span.type()) {
                case BRIDGE, ROAD_CROSSING, PROTECTED_GAP -> true;
                case NETWORK_REUSE -> blockNetworkReuse;
                // Tunnel spans can be physical owners. They are filtered by
                // physicalSpanKindCompatible before a secondary is claimed.
                default -> false;
            };
            if (!blocksReuse) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) blocked[i] = true;
        }
        return blocked;
    }

    static boolean physicalSpanKindCompatible(SpanKind first, SpanKind second) {
        if (first == SpanKind.TUNNEL || second == SpanKind.TUNNEL) {
            return first == SpanKind.TUNNEL && second == SpanKind.TUNNEL;
        }
        return first == SpanKind.ORDINARY && second == SpanKind.ORDINARY;
    }

    static SpanKind[] spanKinds(RoadData road) {
        return spanKinds(road, true);
    }

    /** 按调用用途解释共用段的物理类别，匹配复用时可看真实表面，所有权分配时不能二次认领。
 *
     * @param networkReuseIsOther 为 true（原有语义）时 NETWORK_REUSE 站点算
     *        {@link SpanKind#OTHER}，于是既不能当主人、也不能被认领；
     *        为 false 时它们保留物理类别（通常是 ORDINARY）—— 只用于
     *        {@code matchReuseStations == true} 那一问：「这段让出去的路，
     *        真的有人铺吗」。让出这个标记本身不该成为「没人能铺」的理由。
     */
    static SpanKind[] spanKinds(RoadData road, boolean networkReuseIsOther) {
        int size = road == null || road.roadSegmentList() == null ? 0 : road.roadSegmentList().size();
        SpanKind[] kinds = new SpanKind[size];
        Arrays.fill(kinds, SpanKind.ORDINARY);
        markSpanKind(kinds, road, SpanType.TUNNEL, SpanKind.TUNNEL);
        markSpanKind(kinds, road, SpanType.TUNNEL_JUNCTION, SpanKind.TUNNEL);
        markSpanKind(kinds, road, SpanType.BRIDGE, SpanKind.OTHER);
        markSpanKind(kinds, road, SpanType.ROAD_CROSSING, SpanKind.OTHER);
        markSpanKind(kinds, road, SpanType.PROTECTED_GAP, SpanKind.OTHER);
        if (networkReuseIsOther) {
            markSpanKind(kinds, road, SpanType.NETWORK_REUSE, SpanKind.OTHER);
        }
        return kinds;
    }

    static void markSpanKind(SpanKind[] kinds, RoadData road,
                                     SpanType type, SpanKind kind) {
        // 只索引确实出现的跨度类型，避免一条长路为不存在的类型反复装箱。
        if (road == null || road.spans() == null || kinds.length == 0
                || road.spans().stream().noneMatch(span -> span != null && span.type() == type)) return;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) {
                indices.putIfAbsent(xzKey(segment.middlePos()), i);
            }
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != type || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end) && i < kinds.length; i++) {
                kinds[i] = kind;
            }
        }
    }

    private enum SpanKind {
        ORDINARY,
        TUNNEL,
        OTHER
    }

    static RoadData withPhysicalReuseSpans(RoadData road, boolean[] reused) {
        List<RoadSpan> spans = new ArrayList<>(road.spans());
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        int start = -1;
        for (int i = 0; i <= reused.length; i++) {
            boolean value = i < reused.length && reused[i];
            if (value && start < 0) start = i;
            if (!value && start >= 0) {
                int end = i - 1;
                if (end - start + 1 >= PHYSICAL_REUSE_MIN_RUN) {
                    BlockPos from = segments.get(start).middlePos();
                    BlockPos to = segments.get(end).middlePos();
                    if (from != null && to != null) spans.add(new RoadSpan(from, to, SpanType.NETWORK_REUSE));
                }
                start = -1;
            }
        }
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                segments, spans, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    static boolean footprintsIntersect(RoadSegmentPlacement first, RoadSegmentPlacement second) {
        Set<Long> footprint = new HashSet<>();
        addFootprint(footprint, first);
        if (second.middlePos() != null && footprint.contains(xzKey(second.middlePos()))) return true;
        if (second.positions() != null) {
            for (BlockPos pos : second.positions()) {
                if (pos != null && footprint.contains(xzKey(pos))) return true;
            }
        }
        return false;
    }

    static void addFootprint(Set<Long> footprint, RoadSegmentPlacement placement) {
        if (placement.middlePos() != null) footprint.add(xzKey(placement.middlePos()));
        if (placement.positions() != null) {
            for (BlockPos pos : placement.positions()) {
                if (pos != null) footprint.add(xzKey(pos));
            }
        }
    }

    static long xzKey(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

}
