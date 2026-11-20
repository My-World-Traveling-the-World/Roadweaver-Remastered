/* 文件职责：利用二站高差约束的前缀/后缀最小值，线性完成一层纵断面转移。 */
package net.shiroha233.roadweaver.pathlogic.profile;

/** |d+pd|≤L且pd∈[-L,L]，每个允许区间必贴左端或右端，无需枚举每对高差。 */
final class ProfilePairTransitions {
    private ProfilePairTransitions() {}

    static void advance(ProfileDpSolver.Problem p, int station, int minH, int width,
                        double[] prev, double[] cur, int[] parent) {
        int limit = p.maxStepPerTwo(), count = 2 * limit + 1;
        if (station == 1) {
            // 首对高度还没有前一高差，仍只读取旧dummy状态，保留原边界契约。
            for (int k = 0; k < width; k++) {
                int y = minH + k;
                if (!ProfileDpSolver.allowed(p, station, y)) continue;
                double cost = ProfileDpSolver.costAt(p, station, y);
                for (int di = 0; di < count; di++) {
                    int delta = di - limit, previous = k - delta;
                    if (Math.abs(delta) > p.maxStep() || previous < 0 || previous >= width) continue;
                    int source = previous * count + limit;
                    if (!(prev[source] < Double.POSITIVE_INFINITY)) continue;
                    cur[k * count + di] = prev[source] + cost;
                    parent[k * count + di] = source;
                }
            }
            return;
        }
        for (int previous = 0; previous < width; previous++) {
            int base = previous * count;
            double best = Double.POSITIVE_INFINITY;
            int arg = -1;
            for (int pd = 0; pd < count; pd++) {
                double value = prev[base + pd];
                // 旧算法从小下标枚举，等价解必须保留最小pd，不能随意换比较符。
                if (value < best) { best = value; arg = pd; }
                if (pd >= limit) {
                    int delta = count - 1 - pd;
                    store(p, station, minH, width, previous, delta, count, limit, base, arg, best, cur, parent);
                }
            }
            best = Double.POSITIVE_INFINITY;
            arg = -1;
            for (int pd = count - 1; pd >= 1; pd--) {
                double value = prev[base + pd];
                // 后缀反向扫描，相同代价更新到更小下标；INF/NaN不能伪造可达父节点。
                if (value < best || value == best && value < Double.POSITIVE_INFINITY) {
                    best = value; arg = pd;
                }
                if (pd <= limit)
                    store(p, station, minH, width, previous, -pd, count, limit, base, arg, best, cur, parent);
            }
        }
    }

    private static void store(ProfileDpSolver.Problem p, int station, int minH, int width,
                              int previous, int delta, int count, int limit, int base, int arg,
                              double best, double[] cur, int[] parent) {
        int k = previous + delta;
        if (arg < 0 || Math.abs(delta) > p.maxStep() || k < 0 || k >= width
                || !ProfileDpSolver.allowed(p, station, minH + k)) return;
        int destination = k * count + limit + delta;
        cur[destination] = best + ProfileDpSolver.costAt(p, station, minH + k);
        parent[destination] = base + arg;
    }
}
