/* 文件职责：用已有粗网格确定路、桥、隧的草图，再按坡度传播窗口筛选走廊。 */
package net.shiroha233.roadweaver.planning.terrain;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathfinding.ShortCrossingGeometry;

record CoarseRouteSketch(byte[] modes, int[] heights, double gradeDeficit) {
    static final byte ROAD = 0, CULVERT = 1, SHORT_CROSSING = 2, BRIDGE = 3, TUNNEL = 4;

    /** 跨水高程以两岸和水位为依据；覆土段允许从山下通过，不能用地表高度评价。 */
    static CoarseRouteSketch build(List<BlockPos> path, int[] ground, int[] tunnelY, int[] waterTop, int[] depth,
            int cut, int fill, int cover, double grade, int maxShort, int culvertWidth, int culvertDepth, int dryDrop) {
        int n = ground.length;
        byte[] modes = new byte[n];
        int[] planned = tunnelY.clone();
        double[] upper = new double[n];
        int[] lower = new int[n];
        double[] distance = new double[n];
        boolean[] occupied = new boolean[n];
        for (int i = 0; i < n; i++) {
            upper[i] = ground[i] + fill;
            lower[i] = ground[i] - cut;
            if (i > 0) distance[i] = distance[i - 1] + spacing(path, i);
            if (depth[i] == 0 && ground[i] - tunnelY[i] >= cover) {
                modes[i] = TUNNEL;
                upper[i] = ground[i] - cover;
                lower[i] = tunnelY[i] - cut;
            }
            occupied[i] = depth[i] > 0 || modes[i] == TUNNEL;
        }
        for (var notch : ShortCrossingGeometry.dryNotches(path, ground, dryDrop, occupied)) {
            int left = notch.left(), right = notch.right();
            for (int i = left + 1; i < right; i++) {
                double t = (distance[i] - distance[left]) / (distance[right] - distance[left]);
                int bank = ground[left] + (int) Math.round((ground[right] - ground[left]) * t);
                modes[i] = SHORT_CROSSING;
                lower[i] = planned[i] = bank;
                upper[i] = bank + fill;
            }
        }
        for (int start = 0; start < n; ) {
            if (depth[start] == 0) { start++; continue; }
            int end = start;
            int maxDepth = depth[start], maxSurface = waterTop[start];
            while (end + 1 < n && depth[end + 1] > 0) {
                end++;
                maxDepth = Math.max(maxDepth, depth[end]);
                maxSurface = Math.max(maxSurface, waterTop[end]);
            }
            int left = Math.max(0, start - 1), right = Math.min(n - 1, end + 1);
            double wetWidth = distance[end] - distance[start] + (spacing(path, start) + spacing(path, end + 1)) * 0.5;
            byte mode = wetWidth <= culvertWidth && maxDepth <= culvertDepth ? CULVERT
                    : wetWidth <= maxShort ? SHORT_CROSSING : BRIDGE;
            int floor = maxSurface + 2;
            int bank = Math.max(ground[left], ground[right]);
            for (int i = start; i <= end; i++) {
                modes[i] = mode;
                upper[i] = Math.max(floor, bank + fill);
                double t = left == right ? 0.0 : (distance[i] - distance[left]) / (distance[right] - distance[left]);
                planned[i] = Math.max(floor, (int) Math.round(ground[left] + (ground[right] - ground[left]) * t));
                // 初筛短跨按两岸预计高度，而不是把“可以潜到水面”当成坡度可行。
                lower[i] = mode == BRIDGE ? floor : planned[i];
            }
            start = end + 1;
        }
        // 全线双向可达包络只需两扫，能发现多站接坡不够；逐边检查会放过累计高度矛盾。
        if (depth[0] == 0) upper[0] = lower[0] = ground[0];
        if (depth[n - 1] == 0) upper[n - 1] = lower[n - 1] = ground[n - 1];
        for (int i = 1; i < n; i++) upper[i] = Math.min(upper[i], upper[i - 1] + grade * (distance[i] - distance[i - 1]));
        for (int i = n - 2; i >= 0; i--) upper[i] = Math.min(upper[i], upper[i + 1] + grade * (distance[i + 1] - distance[i]));
        double deficit = 0;
        for (int i = 0; i < n; i++) deficit = Math.max(deficit, lower[i] - upper[i]);
        return new CoarseRouteSketch(modes, planned, deficit);
    }

    private static double spacing(List<BlockPos> path, int i) {
        if (path.size() < 2) return 1;
        i = Math.clamp(i, 1, path.size() - 1);
        var a = path.get(i - 1);
        var b = path.get(i);
        return Math.hypot((double) b.getX() - a.getX(), (double) b.getZ() - a.getZ());
    }

    int[] stationCounts() {
        int[] counts = new int[5];
        for (byte mode : modes) counts[mode]++;
        return counts;
    }

    /** 粗路径缓存格式不变；把已选跨越的高程草图带到下一阶段，供走廊精采和复核。 */
    List<BlockPos> seedCrossings(List<BlockPos> stations) {
        ArrayList<BlockPos> result = null;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == ROAD || modes[i] == TUNNEL) continue;
            var point = stations.get(i);
            if (point.getY() == heights[i]) continue;
            if (result == null) result = new ArrayList<>(stations);
            result.set(i, new BlockPos(point.getX(), heights[i], point.getZ()));
        }
        return result == null ? stations : List.copyOf(result);
    }
}
