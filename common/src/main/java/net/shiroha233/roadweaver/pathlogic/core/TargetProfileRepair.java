/* 文件职责：地表窗口无解时，先确认实际隧道足迹，再在施工许可范围内重新求解。 */
package net.shiroha233.roadweaver.pathlogic.core;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Consumer;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.profile.CostModel;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSolver;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFeasibility;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import static net.shiroha233.roadweaver.pathlogic.core.Road.LOGGER;

/** 隧道分类仍由原物理判据提供；不启用联合模式回写，也不改变桥掩码来源。 */
final class TargetProfileRepair {
    private TargetProfileRepair() {}

    /** 跨度两头是接地洞口；只有两侧仍属于隧道且已有足够埋深的站才锁为覆土洞身。 */
    static boolean[] coveredCore(int[] terrain, int[] reference, boolean[] excavation, int minCover) {
        var core = new boolean[terrain.length];
        for (int i = 1; i + 1 < core.length; i++) {
            core[i] = excavation[i - 1] && excavation[i] && excavation[i + 1]
                    && (long) terrain[i] - reference[i] >= minCover;
        }
        return core;
    }

    static Optional<int[]> solve(int[] terrain, boolean[] crossing, int cut, int fill, int step, int two,
            ProfileDpSource.Constraints constraints, Function<List<Integer>, boolean[]> tunnels) {
        return solve(terrain, crossing, cut, fill, step, two, constraints, tunnels, ignored -> {});
    }

    /** 真实道路保留拒绝见证，调度器才能区分约束冲突与缺粗路线等失败。 */
    static Optional<int[]> solveForRoad(int[] terrain, boolean[] crossing, int cut, int fill, int step, int two,
            ProfileDpSource.Constraints constraints, Function<List<Integer>, boolean[]> tunnels) {
        return solve(terrain, crossing, cut, fill, step, two, constraints, tunnels,
                conflict -> { throw new ProfileConflict.Rejected(conflict); });
    }

    private static Optional<int[]> solve(int[] terrain, boolean[] crossing, int cut, int fill, int step, int two,
            ProfileDpSource.Constraints constraints, Function<List<Integer>, boolean[]> tunnels,
            Consumer<ProfileConflict> rejected) {
        if (rejectCoveredPin(terrain, constraints, rejected)) return Optional.empty();
        var initial = ProfileDpSource.prepare(terrain, crossing, cut, fill, step, two, constraints).problem();
        // 最高合规包络只是用于找山体与洞口的预览，未通过下界证明前绝不作为成品返回。
        var preview = ProfileFeasibility.upperEnvelope(initial.hi(), step, two);
        for (int i = 0; i < terrain.length; i++) {
            // 检查的是锁高存在性，洞口在零点以下也不许移动。
            if (initial.pinned(i) && preview[i] != initial.pin()[i]) {
                rejected.accept(conflictAt(ProfileConflict.Reason.LOCKED_HEIGHT, i, initial.pin()[i],
                        initial.hi(), preview[i], step, two));
                return Optional.empty();
            }
        }
        var excavation = tunnels.apply(Arrays.stream(preview).boxed().toList());
        if (excavation.length != terrain.length) throw new IllegalArgumentException("隧道权限与站数不一致");
        var covered = new boolean[terrain.length];
        for (int i = 0; i < terrain.length; i++) {
            // 跨度包含地表洞口；只有真正埋入山体的洞身要求覆土，不能把洞口也压低八格。
            covered[i] = constraints.covered(i)
                    || excavation[i] && terrain[i] - preview[i] >= constraints.minCover();
        }
        var adjusted = new ProfileDpSource.Constraints(constraints.pin(), covered, constraints.minCover(),
                constraints.deckFloor(), constraints.pinnedStations());
        if (rejectCoveredPin(terrain, adjusted, rejected)) return Optional.empty();
        var prepared = ProfileDpSource.prepare(terrain, crossing, cut, fill, step, two, adjusted).problem();
        var lower = prepared.lo().clone();
        var upper = prepared.hi();
        int floor = Math.min(Arrays.stream(lower).min().orElseThrow(), Arrays.stream(preview).min().orElseThrow());
        int openRepairs = 0, tunnelStations = 0;
        for (int i = 0; i < terrain.length; i++) {
            if (prepared.pinned(i)) continue;
            if (excavation[i]) {
                // 已选隧道的挖深不属于地表路预算；有限高度域仍由本路已采地形限定。
                lower[i] = floor;
                tunnelStations++;
            } else if (preview[i] < lower[i]) {
                // 施工端本来接纳有界局部路堑；只补到可行包络所必需的高度，不整体放大玩家设置。
                if (cut == 0 || (long) terrain[i] - preview[i] > RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH) {
                    LOGGER.warn("【纵断面复核仍超出地表施工预算】 station={} terrain={} requiredY={} cutLimit={}",
                            i, terrain[i], preview[i], RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH);
                    int allowedCut = cut == 0 ? 0 : RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH;
                    rejected.accept(conflictAt(ProfileConflict.Reason.CUT_BUDGET, i, terrain[i] - allowedCut,
                            initial.hi(), preview[i], step, two));
                    return Optional.empty();
                }
                lower[i] = preview[i];
                openRepairs++;
            }
        }
        // 先以线性包络证明窗口有解，再付出 DP 成本；无解不调用旧平滑或提高填方。
        var feasible = ProfileFeasibility.project(terrain, lower, upper, step, two);
        if (feasible.isEmpty()) {
            var roof = ProfileFeasibility.upperEnvelope(upper, step, two);
            for (int i = 0; i < lower.length; i++) {
                if (lower[i] <= roof[i]) continue;
                rejected.accept(conflictAt(ProfileConflict.Reason.SLOPE_WINDOW, i, lower[i],
                        upper, roof[i], step, two));
                return Optional.empty();
            }
            // 理论上包络应给出见证；若实现异常，保留独立分类，不捏造站号。
            rejected.accept(new ProfileConflict(ProfileConflict.Reason.NO_FEASIBLE_PROFILE, -1, -1, 0, 0));
            return Optional.empty();
        }
        var problem = new ProfileDpSolver.Problem(lower, upper,
                CostModel.fromTerrain(terrain, lower, upper, CostModel.Settings.defaults()), step,
                prepared.pin(), two, prepared.pinnedStations());
        var optimized = ProfileDpSolver.solve(problem).map(ProfileDpSolver.Solution::profile);
        LOGGER.info("【纵断面施工窗口复核通过】 stations={} tunnelStations={} localOpenRepairs={} optimized={}",
                terrain.length, tunnelStations, openRepairs, optimized.isPresent());
        // 数学投影已经逐站证明上下界和两种坡限；优化器未返回时可采用同一新版可行解。
        return optimized.isPresent() ? optimized : feasible;
    }

    private static boolean rejectCoveredPin(int[] terrain, ProfileDpSource.Constraints constraints,
                                            Consumer<ProfileConflict> rejected) {
        for (int i = 0; i < terrain.length; i++) {
            if (!constraints.pinned(i) || !constraints.covered(i)) continue;
            int limit = terrain[i] - constraints.minCover();
            if (constraints.pin()[i] <= limit) continue;
            // 同站锁高与覆土互斥，扩大寻路采样不会改变这两个输入。
            rejected.accept(new ProfileConflict(ProfileConflict.Reason.COVER, i, i, constraints.pin()[i], limit));
            return true;
        }
        return false;
    }

    /**
     * 从已传播的上界反查限制站；只在失败后做一趟线性扫描。
     * 每两步可走一条隔站边，或走两条相邻边，取较严格的高差约束。
     */
    private static ProfileConflict conflictAt(ProfileConflict.Reason reason, int station, int minY,
                                              int[] upper, int maxY, int step, int perTwo) {
        int adjacent = perTwo < 0 ? step : Math.min(step, perTwo);
        long pair = perTwo < 0 ? 2L * adjacent : Math.min(2L * adjacent, perTwo);
        // 优先站点本身；窗口自身冲突时，不应误称由远处同值上界导致。
        int limiting = station;
        if (upper[station] != maxY) {
            for (int i = 0; i < upper.length; i++) {
                int distance = Math.abs(i - station);
                if ((long) upper[i] + distance / 2 * pair + (long) (distance % 2) * adjacent == maxY) {
                    limiting = i;
                    break;
                }
            }
        }
        return new ProfileConflict(reason, station, limiting, minY, maxY);
    }
}
