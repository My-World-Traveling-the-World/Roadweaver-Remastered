/* 文件职责：给整场采样与 FMM 提供可复核的数组载荷估算，避免把估算冒充堆峰值。 */
package net.shiroha233.roadweaver.metric;

public record DenseGridWorkload(long cells, long samplingHeightBytes, long retainedHeightBytes,
                                 long solverFixedBytes, long targetBytesUpperBound) {
    public static DenseGridWorkload estimate(GridLayout grid, boolean keepAllHeightMaps,
                                             boolean blockedEdges, int targetCount) {
        if (targetCount < 0) throw new IllegalArgumentException("negative target count");
        // 当前采样接口总是产出三张 int 图；普通拓扑随后只保留行走面，影子试评保留三张。
        long sampling = grid.payloadBytes(3 * Integer.BYTES);
        long retained = grid.payloadBytes((keepAllHeightMaps ? 3 : 1) * Integer.BYTES);
        // 初始口径对应整场数组：双轴代价、通行位、到达值、冻结位；不计对象头和前沿。
        long solver = grid.payloadBytes(3 * Double.BYTES + 2 + (blockedEdges ? 1 : 0));
        long targets = targetCount == 0 ? 0 : grid.payloadBytes(1);
        return new DenseGridWorkload(grid.cellCount(), sampling, retained, solver, targets);
    }

    public long retainedAndSolverPayloadBytes() {
        return Math.addExact(Math.addExact(retainedHeightBytes, solverFixedBytes), targetBytesUpperBound);
    }
}
