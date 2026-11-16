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
        // 双轴代价与到达值仍是整场 double；两个布尔状态压成 long 位图，分别按字取整。
        long solver = Math.addExact(grid.payloadBytes(3 * Double.BYTES + (blockedEdges ? 1 : 0)),
                Math.multiplyExact(grid.bitsetPayloadBytes(), 2));
        long targets = FmmTargets.payloadBytesUpperBound(grid.cellCount(), targetCount);
        return new DenseGridWorkload(grid.cellCount(), sampling, retained, solver, targets);
    }

    public long retainedAndSolverPayloadBytes() {
        return Math.addExact(Math.addExact(retainedHeightBytes, solverFixedBytes), targetBytesUpperBound);
    }
}
