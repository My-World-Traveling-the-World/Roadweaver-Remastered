/* 文件职责：仅在离线候选中缓存当前度量的方向二次型，不新增生产求解路径。 */
package net.shiroha233.roadweaver.metric;

record DirectionalLocalCost(double xx, double zz, double xz) {
    static DirectionalLocalCost sample(FinslerMetric metric, int x, int z) {
        double xx = metric.cost(x, z, 1, 0), zz = metric.cost(x, z, 0, 1);
        // 当前Finsler公式是方向二次型；三方向采样保留障碍势和位置倍率，不能只缓存坡度。
        double xz = metric.cost(x, z, 1, 1) - (xx + zz) / 2;
        return new DirectionalLocalCost(xx, zz, xz);
    }

    double travel(double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length < 1e-12) return length;
        double ux = dx / length, uz = dz / length;
        return length * (xx * ux * ux + zz * uz * uz + 2 * xz * ux * uz);
    }
}
