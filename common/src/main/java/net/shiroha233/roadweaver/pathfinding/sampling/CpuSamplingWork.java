/* 文件职责：隔离精确采样批次的执行方式，防止调度逻辑改变高度语义。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.function.IntConsumer;
import java.util.stream.IntStream;

interface CpuSamplingWork {
    CpuSamplingWork UNRESTRICTED = new CpuSamplingWork() {
        @Override public void parallel(int count, IntConsumer action) {
            IntStream.range(0, count).parallel().forEach(action);
        }
        @Override public void sequential(int count, IntConsumer action) {
            for (int index = 0; index < count; index++) action.accept(index);
        }
    };

    // 区分原本并行的整块/列批次和非噪声生成器的顺序路径，搬迁时不增加并发。
    void parallel(int count, IntConsumer action);
    void sequential(int count, IntConsumer action);
}
