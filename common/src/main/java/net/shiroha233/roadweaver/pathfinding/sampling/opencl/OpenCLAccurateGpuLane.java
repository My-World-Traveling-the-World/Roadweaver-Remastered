/* 文件职责：串行化严格的 GPU 工作，同时允许普通精确工作转交 CPU。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 保留的精确GPU通道协调器串行设备工作；它不代表生产已启用精确GPU。 */
final class OpenCLAccurateGpuLane {
    private final Semaphore permit = new Semaphore(1, true);
    private final AtomicBoolean preferredWaiter = new AtomicBoolean();

    boolean tryAcquire() {
        return permit.tryAcquire();
    }

    void acquire() {
        try {
            permit.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted while waiting for the accurate OpenCL lane");
        }
    }

    /** 历史GPU路径只允许有限排队，避免设备空档与CPU回落相互放大；生产精确采样未启用此路径。
 *
     * During startup/Chunky, keep one request queued for the GPU before
     * diverting later work to CPU. This prevents CPU fallback from creating a
     * visible GPU gap while still allowing one CPU request to run in parallel.
     */
    boolean tryAcquirePreferGpu() {
        if (permit.tryAcquire()) return true;
        if (!preferredWaiter.compareAndSet(false, true)) return false;
        try {
            acquire();
            return true;
        } finally {
            preferredWaiter.set(false);
        }
    }

    void release() {
        permit.release();
    }
}
