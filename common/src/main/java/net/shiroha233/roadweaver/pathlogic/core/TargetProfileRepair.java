/* 文件职责：地表窗口无解时，先确认实际隧道足迹，再在施工许可范围内重新求解。 */
package net.shiroha233.roadweaver.pathlogic.core;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.profile.CostModel;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSolver;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFeasibility;
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
        var initial = ProfileDpSource.prepare(terrain, crossing, cut, fill, step, two, constraints).problem();
        // 最高合规包络只是用于找山体与洞口的预览，未通过下界证明前绝不作为成品返回。
        var preview = ProfileFeasibility.upperEnvelope(initial.hi(), step, two);
        for (int i = 0; i < terrain.length; i++) {
            if (initial.pin()[i] >= 0 && preview[i] != initial.pin()[i]) return Optional.empty();
        }
        var excavation = tunnels.apply(Arrays.stream(preview).boxed().toList());
        if (excavation.length != terrain.length) throw new IllegalArgumentException("隧道权限与站数不一致");
        var covered = new boolean[terrain.length];
        for (int i = 0; i < terrain.length; i++) {
            // 跨度包含地表洞口；只有真正埋入山体的洞身要求覆土，不能把洞口也压低八格。
            covered[i] = constraints.covered(i)
                    || excavation[i] && terrain[i] - preview[i] >= constraints.minCover();
        }
        var prepared = ProfileDpSource.prepare(terrain, crossing, cut, fill, step, two,
                new ProfileDpSource.Constraints(constraints.pin(), covered, constraints.minCover(),
                        constraints.deckFloor())).problem();
        var lower = prepared.lo().clone();
        var upper = prepared.hi();
        int floor = Math.min(Arrays.stream(lower).min().orElseThrow(), Arrays.stream(preview).min().orElseThrow());
        int openRepairs = 0, tunnelStations = 0;
        for (int i = 0; i < terrain.length; i++) {
            if (prepared.pin()[i] >= 0) continue;
            if (excavation[i]) {
                // 已选隧道的挖深不属于地表路预算；有限高度域仍由本路已采地形限定。
                lower[i] = floor;
                tunnelStations++;
            } else if (preview[i] < lower[i]) {
                // 施工端本来接纳有界局部路堑；只补到可行包络所必需的高度，不整体放大玩家设置。
                if (cut == 0 || (long) terrain[i] - preview[i] > RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH) {
                    LOGGER.warn("【纵断面复核仍超出地表施工预算】 station={} terrain={} requiredY={} cutLimit={}",
                            i, terrain[i], preview[i], RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH);
                    return Optional.empty();
                }
                lower[i] = preview[i];
                openRepairs++;
            }
        }
        // 先以线性包络证明窗口有解，再付出 DP 成本；无解不调用旧平滑或提高填方。
        var feasible = ProfileFeasibility.project(terrain, lower, upper, step, two);
        if (feasible.isEmpty()) return Optional.empty();
        var problem = new ProfileDpSolver.Problem(lower, upper,
                CostModel.fromTerrain(terrain, lower, upper, CostModel.Settings.defaults()), step, prepared.pin(), two);
        var optimized = ProfileDpSolver.solve(problem).map(ProfileDpSolver.Solution::profile);
        LOGGER.info("【纵断面施工窗口复核通过】 stations={} tunnelStations={} localOpenRepairs={} optimized={}",
                terrain.length, tunnelStations, openRepairs, optimized.isPresent());
        // 数学投影已经逐站证明上下界和两种坡限；优化器未返回时可采用同一新版可行解。
        return optimized.isPresent() ? optimized : feasible;
    }

}
