package net.shiroha233.roadweaver.pathlogic.validation;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

/** Structural validation applied after all route merging and before persistence. */
public final class RoadDataValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int FINAL_HEIGHT_REPAIR_LOOKBEHIND = 16;
    private static final int FINAL_HEIGHT_REPAIR_LOOKAHEAD = 32;
    private static final int FINAL_HEIGHT_REPAIR_MAX_WINDOW = 128;
    private static final int MAX_PROTECTED_HEIGHT_GAP_DELTA = 3;
    private static final int MAX_GLOBAL_HEIGHT_SMOOTH_PASSES = 4096;
    /** Bounded memory-cache of structural validation results keyed by immutable RoadData contents. */
    private static final ConcurrentHashMap<RoadData, ValidationResult> VALIDATION_RESULTS = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_VALIDATIONS = 4096;

    private RoadDataValidator() {
    }

    public static boolean isValidFinalRoad(RoadData road) {
        return validateFinalRoad(road).valid();
    }

    public static ValidationResult validateFinalRoad(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().size() < 5) {
            return ValidationResult.failure("road-too-short", -1, null,
                    "road or segments are null, or fewer than five stations");
        }
        ValidationResult cached = VALIDATION_RESULTS.get(road);
        if (cached != null) return cached;
        ValidationResult computed = validateFinalRoadUncached(road);
        if (VALIDATION_RESULTS.size() >= MAX_CACHED_VALIDATIONS) {
            VALIDATION_RESULTS.clear();
        }
        VALIDATION_RESULTS.put(road, computed);
        return computed;
    }

    private static ValidationResult validateFinalRoadUncached(RoadData road) {
        // 0.8.321：RoadData 的 roadSegmentList 是 List.copyOf（不可变），而下面在遇到
        // "相邻中心恰好相隔 3 格"时要往上一站的 footprint 里补一个合成站点。旧代码直接在
        // 不可变列表上 set → UnsupportedOperationException，整条路直接判失败
        // （真机 20260909 15.21 存档：(-1104,304)->(-813,716) 与 (0,0)->(97,281) 两条
        // 因此 ERROR 失败）。改为在可变副本上操作：校验语义不变，调用方的 road 依旧不被修改。
        List<RoadSegmentPlacement> segments = new ArrayList<>(road.roadSegmentList());
        List<Integer> targetY = road.targetY();
        if (targetY == null || targetY.size() != segments.size()) {
            return ValidationResult.failure("target-height-size", -1, null,
                    "segments=" + segments.size() + " targetY="
                            + (targetY == null ? "null" : targetY.size()));
        }

        // A final-height smoothing pass inserts ordinary stations between two
        // centers that drifted to three blocks apart. Keep those synthetic
        // stations in the footprint list so they still render as road rather
        // than an unclassified three-block center jump.
        for (int i = 1; i < segments.size(); i++) {
            BlockPos previous = segments.get(i - 1).middlePos();
            BlockPos current = segments.get(i).middlePos();
            if (previous == null || current == null) continue;
            if (Math.max(Math.abs(current.getX() - previous.getX()),
                    Math.abs(current.getZ() - previous.getZ())) != 3) continue;
            int gapX = current.getX() - previous.getX();
            int gapZ = current.getZ() - previous.getZ();
            int insertedX = previous.getX() + Integer.signum(gapX);
            int insertedZ = previous.getZ() + Integer.signum(gapZ);
            int insertedY = (previous.getY() + current.getY()) / 2;
            BlockPos inserted = new BlockPos(insertedX, insertedY, insertedZ);
            RoadSegmentPlacement previousSegment = segments.get(i - 1);
            List<BlockPos> expanded = previousSegment == null || previousSegment.positions() == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(previousSegment.positions());
            if (!expanded.contains(inserted)) expanded.add(inserted);
            segments.set(i - 1,
                    new RoadSegmentPlacement(previous, java.util.List.copyOf(expanded)));
        }

        Map<Long, List<Integer>> centerHeights = new HashMap<>();
        Map<Long, Integer> indices = new HashMap<>();
        Map<Long, Integer> positionIndices = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment == null || segment.middlePos() == null
                    || segment.positions() == null || segment.positions().isEmpty()
                    || targetY.get(i) == null) {
                return ValidationResult.failure("invalid-station", i,
                        segment == null ? null : segment.middlePos(),
                        "missing center, footprint, or target height");
            }
            BlockPos center = segment.middlePos();
            long key = xzKey(center);
            List<Integer> existingHeights = centerHeights.computeIfAbsent(key, ignored -> new java.util.ArrayList<>());
            for (int existingY : existingHeights) {
                if (Math.abs(existingY - targetY.get(i)) <= 1) {
                    // Network reuse can intentionally place two approaches at
                    // one X/Z station. normalizeDuplicateJunctions() records
                    // the explicit junction metadata before persistence.
                    break;
                }
            }
            existingHeights.add(targetY.get(i));
            indices.put(xzKey(center), i);
            positionIndices.putIfAbsent(positionKey(center), i);
            if (i == 0) continue;
            BlockPos previous = segments.get(i - 1).middlePos();
            int step = Math.max(Math.abs(center.getX() - previous.getX()), Math.abs(center.getZ() - previous.getZ()));
            if (step == 0) {
                int heightDelta = Math.abs(targetY.get(i) - targetY.get(i - 1));
                if (heightDelta <= 1) continue;
                return ValidationResult.failure("invalid-center-step", i, center,
                        "previous=" + previous + " horizontalStep=0 heightDelta=" + heightDelta);
            }
            if (step > RoadConstants.MAX_CENTER_STEP) {
                return ValidationResult.failure("invalid-center-step", i, center,
                        "previous=" + previous + " horizontalStep=" + step);
            }
        }

        if (road.spans() == null) {
            return ValidationResult.failure("null-spans", -1, null, "span list is null");
        }
        boolean[] bridge = new boolean[segments.size()];
        boolean[] crossing = new boolean[segments.size()];
        boolean[] tunnel = new boolean[segments.size()];
        boolean[] tunnelJunction = new boolean[segments.size()];
        boolean[] protectedGap = new boolean[segments.size()];
        Set<String> spanKeys = new HashSet<>();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null || span.type() == null) {
                return ValidationResult.failure("invalid-span", -1,
                        span == null ? null : span.start(), "missing span endpoint or type");
            }
            Integer start = lookupSpanIndex(positionIndices, indices, span.start());
            Integer end = lookupSpanIndex(positionIndices, indices, span.end());
            // A one-station protected gap is valid: a narrow building can
            // occupy only one sampled station. Other physical spans still
            // require two distinct endpoints.
            if (start == null || end == null
                    || (start.equals(end)
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.PROTECTED_GAP
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.JUNCTION_ANCHOR)) {
                return ValidationResult.failure("invalid-span-endpoint", -1, span.start(),
                        "type=" + span.type() + " startIndex=" + start + " endIndex=" + end
                                + " end=" + span.end());
            }
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            String spanKey = span.type() + ":" + from + ":" + to;
            if (!spanKeys.add(spanKey)) {
                // Refinement and network reuse can contribute the same
                // physical span more than once. It is idempotent; do not
                // discard an otherwise usable road for duplicate metadata.
                continue;
            }
            boolean[] physical = switch (span.type()) {
                case BRIDGE -> bridge;
                case ROAD_CROSSING -> crossing;
                case TUNNEL -> tunnel;
                default -> null;
            };
            if (physical != null) {
                for (int i = from; i <= to; i++) physical[i] = true;
            }
            if (span.type() == net.shiroha233.roadweaver.core.model.SpanType.TUNNEL_JUNCTION) {
                for (int i = from; i <= to; i++) tunnelJunction[i] = true;
            }
            if (span.type() == net.shiroha233.roadweaver.core.model.SpanType.PROTECTED_GAP) {
                for (int i = from; i <= to; i++) protectedGap[i] = true;
            }
        }
        for (int i = 0; i < segments.size(); i++) {
            // A bridge is the concrete crossing implementation. A narrow
            // water marker covering the same stations is only descriptive and
            // must not make the bridge look like two physical spans.
            //
            // 0.9.001：tunnel 覆盖的站同样吃掉 bridge / crossing。
            // PathSpanExtractor 在解析重叠物理 span 时早已按「tunnel wins」裁剪
            // （其日志 "resolved physical span overlap: tunnel wins"），校验器必须
            // 认同同一语义；否则每条被裁剪过的路都会在这里被判成
            // overlapping-physical-spans 而整条丢弃。tunnel[i] 为真说明隧道确实
            // 覆盖该站，不是凭空放行；只影响重叠站，非重叠站的判定逐位不变。
            if (tunnel[i]) {
                bridge[i] = false;
                crossing[i] = false;
            }
            if (bridge[i]) crossing[i] = false;
            int physicalCount = (bridge[i] ? 1 : 0) + (crossing[i] ? 1 : 0) + (tunnel[i] ? 1 : 0);
            if (physicalCount > 1) {
                return ValidationResult.failure("overlapping-physical-spans", i,
                        segments.get(i).middlePos(),
                        "bridge=" + bridge[i] + " crossing=" + crossing[i] + " tunnel=" + tunnel[i]
                                + " spans=" + describePhysicalSpans(road, positionIndices, indices, i));
            }
            if (tunnelJunction[i] && (bridge[i] || crossing[i])) {
                return ValidationResult.failure("junction-crossing-overlap", i,
                        segments.get(i).middlePos(),
                        "bridge=" + bridge[i] + " crossing=" + crossing[i]
                                + " spans=" + describePhysicalSpans(road, positionIndices, indices, i));
            }
        }
        int heightStepExceptions = 0;
        for (int i = 1; i < segments.size(); i++) {
            int heightStep = Math.abs(targetY.get(i) - targetY.get(i - 1));
            if (heightStep > RoadConstants.MAX_HEIGHT_STEP && !protectedGap[i] && !protectedGap[i - 1]) {
                if (++heightStepExceptions <= RoadConstants.MAX_HEIGHT_STEP_EXCEPTIONS) continue;
                return ValidationResult.failure("invalid-height-step", i, segments.get(i).middlePos(),
                        "previous=" + segments.get(i - 1).middlePos()
                                + " previousTargetY=" + targetY.get(i - 1)
                                + " targetY=" + targetY.get(i) + " heightStep=" + heightStep
                                + " exceptions=" + heightStepExceptions);
            }
        }
        return ValidationResult.success();
    }

    /**
     * Junction and reuse post-processing can join otherwise valid profiles at
     * heights one block too far apart. Repair only that final local profile;
     * this is deliberately not a new route search.
     */
    public static RoadData repairFinalHeightSteps(RoadData road) {
        // 0.9.013：planningRepair 段的另一个子项计时（见 DetailedRoadGenerationStats）。
        long startedAt = System.nanoTime();
        try {
            return repairFinalHeightStepsImpl(road);
        } finally {
            net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats
                    .recordHeightStepRepair(System.nanoTime() - startedAt);
        }
    }

    private static RoadData repairFinalHeightStepsImpl(RoadData road) {
        ValidationResult validation = validateFinalRoad(road);
        if (validation.valid() || !"invalid-height-step".equals(validation.reason())
                || validation.index() <= 0 || road == null || road.targetY() == null) {
            return road;
        }

        int last = road.targetY().size() - 1;
        for (int expansion = 0; expansion < 4; expansion++) {
            int lookBehind = Math.min(FINAL_HEIGHT_REPAIR_MAX_WINDOW,
                    FINAL_HEIGHT_REPAIR_LOOKBEHIND + expansion * 24);
            int lookAhead = Math.min(FINAL_HEIGHT_REPAIR_MAX_WINDOW,
                    FINAL_HEIGHT_REPAIR_LOOKAHEAD + expansion * 32);
            int from = Math.max(0, validation.index() - lookBehind);
            int to = Math.min(last, validation.index() + lookAhead);
            List<Integer> heights = new ArrayList<>(road.targetY());

            // Pull the profile forward from the last safe point, then back
            // from the next safe point. The two passes keep the repair local
            // while allowing a long, gradual ramp instead of a hard cut.
            for (int index = Math.max(1, from + 1); index <= to; index++) {
                int previous = heights.get(index - 1);
                int current = heights.get(index);
                if (Math.abs(current - previous) > RoadConstants.MAX_HEIGHT_STEP) {
                    heights.set(index, previous + Integer.signum(current - previous));
                }
            }
            for (int index = Math.min(last - 1, to - 1); index >= from; index--) {
                int current = heights.get(index);
                int next = heights.get(index + 1);
                if (Math.abs(next - current) > RoadConstants.MAX_HEIGHT_STEP) {
                    heights.set(index, next - Integer.signum(next - current));
                }
            }

            RoadData repaired = new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                    road.roadSegmentList(), road.spans(), List.copyOf(heights), road.ownerA2dKey(), road.ownerB2dKey());
            if (validateFinalRoad(repaired).valid()) return repaired;
        }

        // A route may contain several separated two-block transitions. The
        // old bounded window only repaired the first reported transition, so
        // the fourth one could still trip RoadConstants.MAX_HEIGHT_STEP_EXCEPTIONS. Smooth
        // every ordinary edge in repeated forward/backward passes, leaving
        // explicit protected-gap and physical crossing edges untouched.
        RoadData globallySmoothed = smoothAllOrdinaryHeightSteps(road);
        if (globallySmoothed != road && validateFinalRoad(globallySmoothed).valid()) {
            return globallySmoothed;
        }

        // If bounded smoothing cannot produce a valid profile, preserve the
        // route and leave only small offending transitions unpaved. The old
        // implementation marked just the first reported transition; a later
        // fourth (or subsequent) two-block step then invalidated the whole
        // route again. Collect every local <=3-block step in this profile so
        // each one is an explicit construction gap and the valid sections
        // remain usable.
        List<RoadSpan> spans = new ArrayList<>(road.spans() == null ? List.of() : road.spans());
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        for (int gapIndex = 1; gapIndex < segments.size(); gapIndex++) {
            Integer previous = road.targetY().get(gapIndex - 1);
            Integer current = road.targetY().get(gapIndex);
            if (previous == null || current == null
                    || Math.abs(current - previous) > MAX_PROTECTED_HEIGHT_GAP_DELTA) continue;
            if (Math.abs(current - previous) <= RoadConstants.MAX_HEIGHT_STEP) continue;
            BlockPos gapStart = segments.get(gapIndex - 1).middlePos();
            BlockPos gapEnd = segments.get(gapIndex).middlePos();
            if (gapStart == null || gapEnd == null) continue;
            boolean alreadyMarked = spans.stream().anyMatch(span -> span != null
                    && span.type() == net.shiroha233.roadweaver.core.model.SpanType.PROTECTED_GAP
                    && ((gapStart.equals(span.start()) && gapEnd.equals(span.end()))
                    || (gapStart.equals(span.end()) && gapEnd.equals(span.start()))));
            if (!alreadyMarked) {
                LOGGER.warn("RoadWeaver protected-gap height step idx={}..{} from=({},y={}) to=({},y={}) delta={} owners={} -> {}",
                        gapIndex - 1, gapIndex,
                        gapStart.getX(), previous, gapStart.getZ(),
                        gapEnd.getX(), current, gapEnd.getZ(),
                        Math.abs(current - previous), road.ownerA2dKey(), road.ownerB2dKey());
                spans.add(new RoadSpan(gapStart, gapEnd,
                        net.shiroha233.roadweaver.core.model.SpanType.PROTECTED_GAP));
            }
        }
        RoadData gapped = new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), List.copyOf(spans), road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
        return validateFinalRoad(gapped).valid() ? gapped : road;
    }

    private static RoadData smoothAllOrdinaryHeightSteps(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.targetY() == null
                || road.targetY().size() != road.roadSegmentList().size()) return road;
        int size = road.targetY().size();
        if (size < 2) return road;
        boolean[] protectedEdges = new boolean[size];
        if (road.spans() != null) {
            Map<Long, Integer> indices = new HashMap<>();
            for (int i = 0; i < size; i++) {
                RoadSegmentPlacement segment = road.roadSegmentList().get(i);
                if (segment != null && segment.middlePos() != null) {
                    indices.putIfAbsent(xzKey(segment.middlePos()), i);
                }
            }
            for (RoadSpan span : road.spans()) {
                if (span == null || span.start() == null || span.end() == null) continue;
                if (span.type() != net.shiroha233.roadweaver.core.model.SpanType.PROTECTED_GAP
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.BRIDGE
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.ROAD_CROSSING
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.TUNNEL
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.TUNNEL_JUNCTION
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.NETWORK_REUSE) continue;
                Integer start = indices.get(xzKey(span.start()));
                Integer end = indices.get(xzKey(span.end()));
                if (start == null || end == null) continue;
                for (int i = Math.min(start, end); i < Math.max(start, end) && i + 1 < size; i++) {
                    protectedEdges[i + 1] = true;
                }
            }
        }

        List<Integer> heights = new ArrayList<>(road.targetY());
        boolean changed = false;
        for (int pass = 0; pass < MAX_GLOBAL_HEIGHT_SMOOTH_PASSES; pass++) {
            boolean passChanged = false;
            for (int i = 1; i < size; i++) {
                if (protectedEdges[i]) continue;
                int previous = heights.get(i - 1);
                int current = heights.get(i);
                if (Math.abs(current - previous) > RoadConstants.MAX_HEIGHT_STEP) {
                    heights.set(i, previous + Integer.signum(current - previous));
                    passChanged = true;
                }
            }
            for (int i = size - 1; i > 0; i--) {
                if (protectedEdges[i]) continue;
                int current = heights.get(i);
                int next = heights.get(i - 1);
                if (Math.abs(current - next) > RoadConstants.MAX_HEIGHT_STEP) {
                    heights.set(i - 1, current - Integer.signum(current - next));
                    passChanged = true;
                }
            }
            changed |= passChanged;
            if (!passChanged) break;
        }
        if (!changed) return road;
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), road.spans(), List.copyOf(heights),
                road.ownerA2dKey(), road.ownerB2dKey());
    }

    /** Marks repeated X/Z stations from network reuse as explicit junction anchors. */
    public static RoadData normalizeDuplicateJunctions(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.targetY() == null
                || road.targetY().size() != road.roadSegmentList().size()) return road;
        Map<Long, Integer> firstByXz = new HashMap<>();
        List<RoadSpan> spans = new ArrayList<>(road.spans() == null ? List.of() : road.spans());
        Set<String> anchors = new HashSet<>();
        for (RoadSpan span : spans) {
            if (span != null && span.type() == net.shiroha233.roadweaver.core.model.SpanType.JUNCTION_ANCHOR
                    && span.start() != null) {
                anchors.add(xzKey(span.start()) + ":" + span.start().getY());
            }
        }
        boolean changed = false;
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            BlockPos center = segment == null ? null : segment.middlePos();
            if (center == null) continue;
            long xz = xzKey(center);
            if (firstByXz.putIfAbsent(xz, 1) == null) continue;
            String anchorKey = xz + ":" + center.getY();
            if (anchors.add(anchorKey)) {
                spans.add(new RoadSpan(center, center,
                        net.shiroha233.roadweaver.core.model.SpanType.JUNCTION_ANCHOR));
                changed = true;
            }
        }
        return changed ? new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), List.copyOf(spans), road.targetY(), road.ownerA2dKey(), road.ownerB2dKey()) : road;
    }

    public record ValidationResult(boolean valid,
                                   String reason,
                                   int index,
                                   BlockPos position,
                                   String detail) {
        private static ValidationResult success() {
            return new ValidationResult(true, "ok", -1, null, "");
        }

        private static ValidationResult failure(String reason,
                                                int index,
                                                BlockPos position,
                                                String detail) {
            return new ValidationResult(false, reason, index, position, detail);
        }
    }

    /**
     * Accepts structurally usable roads written by older releases and fills
     * the construction profile that predates target_y. New and replacement
     * roads must still pass {@link #isValidFinalRoad(RoadData)}.
     */
    public static RoadData normalizeLegacyRoad(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().size() < 5) return null;
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        Set<Long> centers = new HashSet<>();
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null
                    || segment.positions() == null || segment.positions().isEmpty()) return null;
            centers.add(xzKey(segment.middlePos()));
        }

        List<Integer> normalizedY = new ArrayList<>(segments.size());
        List<Integer> sourceY = road.targetY();
        for (int i = 0; i < segments.size(); i++) {
            Integer height = sourceY != null && i < sourceY.size() ? sourceY.get(i) : null;
            normalizedY.add(height != null ? height : segments.get(i).middlePos().getY());
        }

        List<RoadSpan> normalizedSpans = new ArrayList<>();
        Set<String> spanKeys = new HashSet<>();
        if (road.spans() != null) {
            for (RoadSpan span : road.spans()) {
                if (span == null || span.start() == null || span.end() == null || span.type() == null
                        || !centers.contains(xzKey(span.start())) || !centers.contains(xzKey(span.end()))) continue;
                if (span.start().equals(span.end())
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.PROTECTED_GAP
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.JUNCTION_ANCHOR) continue;
                long first = Math.min(xzKey(span.start()), xzKey(span.end()));
                long second = Math.max(xzKey(span.start()), xzKey(span.end()));
                String key = span.type() + ":" + first + ":" + second;
                if (spanKeys.add(key)) normalizedSpans.add(span);
            }
        }
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                segments, normalizedSpans, normalizedY, road.ownerA2dKey(), road.ownerB2dKey());
    }

    private static long xzKey(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    /** 0.8.327：含高度的站点键，(x,z,y) 三元组位打包。 */
    private static long positionKey(BlockPos pos) {
        return ((long) (pos.getX() & 0x3FFFFFFL) << 38)
                | ((long) (pos.getZ() & 0x3FFFFFFL) << 12)
                | (pos.getY() & 0xFFFL);
    }

    /**
     * 0.9.001：列出覆盖该站的物理 span（含 type 与 from..to 站号），只用于失败 detail。
     *
     * <p>三个布尔只能说明"有两个物理 span 同时为真"，看不出是哪两个、各自覆盖
     * 哪些站，也无法判断是桥/隧道重叠还是 junction 与桥重叠。这里按与主循环
     * 完全相同的端点定位（{@link #lookupSpanIndex}）重放一遍 span 列表，
     * 只读、不参与任何判定。</p>
     */
    private static String describePhysicalSpans(RoadData road,
                                                Map<Long, Integer> positionIndices,
                                                Map<Long, Integer> xzIndices,
                                                int station) {
        if (road.spans() == null) return "[]";
        List<String> parts = new ArrayList<>();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() == null
                    || span.start() == null || span.end() == null) continue;
            if (span.type() != net.shiroha233.roadweaver.core.model.SpanType.BRIDGE
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.ROAD_CROSSING
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.TUNNEL
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.TUNNEL_JUNCTION) {
                continue;
            }
            Integer start = lookupSpanIndex(positionIndices, xzIndices, span.start());
            Integer end = lookupSpanIndex(positionIndices, xzIndices, span.end());
            if (start == null || end == null) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (station < from || station > to) continue;
            parts.add(span.type() + " " + from + ".." + to);
        }
        return "[" + String.join(", ", parts) + "]";
    }

    /**
     * 0.8.327：span 端点定位优先按 (x,z,y) 精确匹配，匹配不到才退回 xz。
     *
     * <p>同一个 xz 上可能存在两站（垂直落差、网络复用两端），只用 xz 定位会把
     * span 的两端映到同一个索引，被 {@code invalid-span-endpoint} 误判成
     * "单站物理 span"（真机 20260909 19.53 存档 {@code (-1104,304)->(-813,716)}
     * 的 ROAD_CROSSING：两端同 xz、y 差 2，整条路因此被丢）。</p>
     */
    private static Integer lookupSpanIndex(Map<Long, Integer> positionIndices,
                                           Map<Long, Integer> xzIndices,
                                           BlockPos pos) {
        if (pos == null) return null;
        Integer exact = positionIndices.get(positionKey(pos));
        return exact != null ? exact : xzIndices.get(xzKey(pos));
    }
}
