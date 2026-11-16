/* 文件职责：给整场采样与 FMM 提供稠密参考和全访问分页载荷估算，避免把估算冒充堆峰值。 */
package net.shiroha233.roadweaver.metric;

public record DenseGridWorkload(long cells, long samplingHeightBytes, long retainedHeightBytes,
                                 long solverFixedBytes, long targetBytesUpperBound) {
    public static DenseGridWorkload estimate(GridLayout grid, boolean keepAllHeightMaps,
                                             boolean blockedEdges, int targetCount) {
        if (targetCount < 0) throw new IllegalArgumentException("negative target count");
        // 当前采样接口总是产出三张 int 图；普通拓扑随后只保留行走面，影子试评保留三张。
        long sampling = grid.payloadBytes(3 * Integer.BYTES);
        long retained = grid.payloadBytes((keepAllHeightMaps ? 3 : 1) * Integer.BYTES);
        // 该值保留稠密存储的参考口径；不能当作按需页的即时占用。
        long solver = Math.addExact(grid.payloadBytes(3 * Double.BYTES + (blockedEdges ? 1 : 0)),
                Math.multiplyExact(grid.bitsetPayloadBytes(), 2));
        long targets = FmmTargets.payloadBytesUpperBound(grid.cellCount(), targetCount);
        return new DenseGridWorkload(grid.cellCount(), sampling, retained, solver, targets);
    }

    /** 全部页都访问时的原始载荷；目录、对象头、前沿和采样器仍须另计。 */
    public long pagedSolverFullVisitPayloadBytes() {
        // 两轴代价 + 到达值共三张 double，页内状态一字节，冻结状态按字取整。
        return Math.addExact(Math.multiplyExact(cells, 3L * Double.BYTES + 1),
                Math.multiplyExact((cells + 63) / 64, Long.BYTES));
    }

    public long retainedAndSolverPayloadBytes() {
        return Math.addExact(Math.addExact(retainedHeightBytes, solverFixedBytes), targetBytesUpperBound);
    }
}
