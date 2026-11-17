/* 文件职责：以世界和完整端点构成观测标识，不参与道路身份、缓存或存档协议。 */
package net.shiroha233.roadweaver.planning;

public final class RouteEvidenceId {
    private RouteEvidenceId() {}

    public static long endpoint(int x, int z) {
        return ((long)x << 32) | (z & 0xffffffffL);
    }

    public static String of(String dimension, long seed, long a, long b) {
        // 观测要能跨阶段、正反方向关联；完整保留端点，避免短哈希碰撞合并两条证据。
        long first = Math.min(a, b), second = Math.max(a, b);
        return dimension + "@" + seed + "/" + (int)(first >> 32) + "," + (int)first
                + "~" + (int)(second >> 32) + "," + (int)second;
    }
}
