/* 文件职责：汇总有限粗路线的可建性证据，不参与生产选边。 */
package net.shiroha233.roadweaver.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BuildabilityShadow {
    private BuildabilityShadow() {}

    enum Protection { UNKNOWN, CLEAR, HIT }
    enum Status { SCORED, INCOMPLETE, NO_TRACE }

    // 水证据用三态：正值是湿列，0 是已确认干列，负值是没证据。
    record Column(int height, int waterDepth, Protection protection) {}

    @FunctionalInterface
    interface Probe {
        Column at(int x, int z);
    }

    record Choice(int from, int to, double geodesic, boolean selected) {}

    record Evidence(Choice choice, int columns, double wetRunBlocks, int maxWaterDepth,
                    int unknownWater, int protectedColumns, int unknownProtection,
                    double gradeDeviation, double grade, Status status, Set<Long> wetCells) {
        @Override
        public String toString() {
            // 不打印完整采样坐标集合，避免开启观测反而被日志 I/O 拖慢。
            return choice + " status=" + status + " columns=" + columns
                    + " wetRunBlocks=" + wetRunBlocks + " maxWaterDepth=" + maxWaterDepth
                    + " unknownWater=" + unknownWater + " protected=" + protectedColumns
                    + " unknownProtection=" + unknownProtection + " gradeDeviation=" + gradeDeviation
                    + " chordGrade=" + grade;
        }
    }

    record Report(String state, int selectedTotal, int solves, List<Evidence> edges,
                  List<Choice> completeOrder, List<Choice> gradeOnlyOrder, int sharedWetCells) {
        static Report summarize(int selectedTotal, int solves, List<Evidence> edges) {
            Set<Long> seen = new HashSet<>(), shared = new HashSet<>();
            for (Evidence evidence : edges) {
                for (long cell : evidence.wetCells()) {
                    if (!seen.add(cell)) shared.add(cell);
                }
            }
            // 缺水或保护证据的边不能被当作零风险。另报只看高程的排序供同种子对照。
            List<Choice> complete = edges.stream().filter(e -> e.status() == Status.SCORED)
                    .sorted(Comparator.comparingInt(Evidence::protectedColumns)
                            .thenComparingDouble(Evidence::wetRunBlocks)
                            .thenComparingDouble(Evidence::gradeDeviation)
                            .thenComparingDouble(e -> e.choice().geodesic()))
                    .map(Evidence::choice).toList();
            List<Choice> gradeOnly = edges.stream().filter(e -> e.status() != Status.NO_TRACE)
                    .sorted(Comparator.comparingDouble(Evidence::gradeDeviation)
                            .thenComparingDouble(e -> e.choice().geodesic()))
                    .map(Evidence::choice).toList();
            return new Report("sampled", selectedTotal, solves, List.copyOf(edges), complete,
                    gradeOnly, shared.size());
        }

        static Report skipped(String reason, int selectedTotal) {
            return new Report(reason, selectedTotal, 0, List.of(), List.of(), List.of(), 0);
        }
    }

    static Evidence measure(Choice choice, List<int[]> path, Probe probe) {
        // 同格回溯没有测过端点短接线，不能算作可建性已通过。
        if (path.size() < 2) {
            return new Evidence(choice, path.size(), 0, 0, 0, 0, 0,
                    Double.NaN, Double.NaN, Status.NO_TRACE, Set.of());
        }
        double length = 0.0;
        for (int i = 1; i < path.size(); i++) length += arc(path.get(i - 1), path.get(i));

        List<Column> columns = new ArrayList<>(path.size());
        for (int[] xz : path) columns.add(probe.at(xz[0], xz[1]));
        double start = columns.getFirst().height(), rise = columns.getLast().height() - start;
        double distance = 0, deviation = 0, run = 0, maxWetRun = 0, previousError = 0;
        int unknownWater = 0, unknownProtection = 0, protectedColumns = 0, maxDepth = 0;
        Set<Long> wetCells = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            Column here = columns.get(i);
            double step = i == 0 ? 0 : arc(path.get(i - 1), path.get(i));
            distance += step;
            // 积分近似是纵向挖填代理量，未乘路宽，也不代替 DP 或原列校验。
            double error = Math.abs(here.height() - (start + rise * distance / length));
            deviation += (previousError + error) * step / 2;
            previousError = error;
            if (here.waterDepth() < 0) unknownWater++;
            if (here.protection() == Protection.UNKNOWN) unknownProtection++;
            if (here.protection() == Protection.HIT) protectedColumns++;
            if (here.waterDepth() > 0) {
                int[] xz = path.get(i);
                wetCells.add(((long) xz[0] << 32) | (xz[1] & 0xffffffffL));
                maxDepth = Math.max(maxDepth, here.waterDepth());
                // 两个都观测为湿才能计入连续湿段，不跨缺样补长。
                if (i > 0 && columns.get(i - 1).waterDepth() > 0) run += step;
                maxWetRun = Math.max(maxWetRun, run);
            } else {
                run = 0;
            }
        }
        Status status = unknownWater == 0 && unknownProtection == 0 ? Status.SCORED : Status.INCOMPLETE;
        return new Evidence(choice, columns.size(), maxWetRun, maxDepth, unknownWater,
                protectedColumns, unknownProtection, deviation, Math.abs(rise) / length,
                status, Set.copyOf(wetCells));
    }

    private static double arc(int[] a, int[] b) {
        return Math.hypot((double) a[0] - b[0], (double) a[1] - b[1]);
    }
}
