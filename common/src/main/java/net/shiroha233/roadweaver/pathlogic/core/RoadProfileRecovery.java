/* 文件职责：真实纵断面拒绝后，复核同端点的地表候选。 */
package net.shiroha233.roadweaver.pathlogic.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.pathlogic.pathcalc.PathMetricsOps;

/** 在道路落盘前复算替代线；冲突见证对应的是当次站序，不能只把两个索引交给别的路线。 */
final class RoadProfileRecovery {
    record Profile(List<RoadSegmentPlacement> segments, List<RoadSpan> spans, List<Integer> heights) {}

    @FunctionalInterface
    interface Search {
        void tryAround(List<BlockPos> line, int station, Predicate<List<BlockPos>> test);
    }

    private RoadProfileRecovery() {}

    static Profile solve(List<RoadSegmentPlacement> segments, int width,
            Function<List<RoadSegmentPlacement>, Profile> profiles, Search search) {
        try {
            return profiles.apply(segments);
        } catch (ProfileConflict.Rejected failure) {
            var conflict = failure.conflict();
            var line = segments.stream().map(RoadSegmentPlacement::middlePos).toList();
            int station = repairStation(conflict, line.size());
            if (station < 0) throw failure;
            Road.LOGGER.info("【纵断面冲突进入地表绕行】 拒绝站={} 限制站={} 修复中心={} 所需高度={} 可达高度={} 类型={}",
                    line.get(conflict.station()), line.get(conflict.limitingStation()), line.get(station),
                    conflict.requiredMinY(), conflict.reachableMaxY(), conflict.reason());
            var accepted = new AtomicReference<Profile>();
            search.tryAround(line, station, candidate -> {
                // 两端接入位置固定；沿用正式栅格化规则，让坡度 DP 按实际施工站距重新求解。
                if (!sameColumn(line.getFirst(), candidate.getFirst()) || !sameColumn(line.getLast(), candidate.getLast())) return false;
                var rebuilt = PathMetricsOps.rasterizeLocalRepair(candidate, width);
                if (rebuilt.isEmpty() || sameGeometry(segments, rebuilt)) return false;
                try {
                    accepted.set(profiles.apply(rebuilt));
                    return true;
                } catch (ProfileConflict.Rejected nextConflict) {
                    Road.LOGGER.info("【地表绕行仍未通过纵断面】 原因={}", nextConflict.conflict());
                    return false;
                }
            });
            if (accepted.get() != null) return accepted.get();
            throw failure;
        }
    }

    /** 河岸延伸重新求解；干谷只分类最终路面，不能再钉回原始陡岸。 */
    static Profile finish(Profile initial,
            Function<List<RoadSpan>, List<RoadSpan>> extendRiverBanks,
            Function<List<RoadSpan>, Profile> solve,
            Function<Profile, List<RoadSpan>> classifyDry) {
        var current = initial;
        for (int pass = 0; pass < 3; pass++) {
            var extended = extendRiverBanks.apply(current.spans());
            if (extended.equals(current.spans())) break;
            current = solve.apply(extended);
        }
        // 干谷来自最终路面与最低占地的差值；重新求桥弦会覆盖已经合法的缓坡。
        return new Profile(current.segments(), classifyDry.apply(current), current.heights());
    }

    static int repairStation(ProfileConflict conflict, int size) {
        int station = conflict.station(), limiter = conflict.limitingStation();
        if (station < 0 || station >= size || limiter < 0 || limiter >= size) return -1;
        // 固定入口本身不可搬；优先绕过限制它的低洼点。挖方卡在独立山体时改绕该山体。
        return switch (conflict.reason()) {
            case LOCKED_HEIGHT -> limiter > 0 && limiter < size - 1 ? limiter : (station + limiter) / 2;
            case CUT_BUDGET, SLOPE_WINDOW -> station;
            case COVER, NO_FEASIBLE_PROFILE -> -1;
        };
    }

    private static boolean sameGeometry(List<RoadSegmentPlacement> a, List<RoadSegmentPlacement> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!sameColumn(a.get(i).middlePos(), b.get(i).middlePos())) return false;
        }
        return true;
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }
}
