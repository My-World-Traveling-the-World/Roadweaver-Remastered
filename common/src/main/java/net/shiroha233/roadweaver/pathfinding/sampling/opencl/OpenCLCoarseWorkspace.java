package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import net.shiroha233.roadweaver.pathfinding.sampling.opencl.bridge.OpenCLBridge;

/** Reuses the large mutable buffers used by consecutive coarse GPU batches. */
final class OpenCLCoarseWorkspace implements AutoCloseable {
    private final ReusableBuffer params = new ReusableBuffer(OpenCLBridge.BufferAccess.READ_ONLY);
    private final ReusableBuffer scratch = new ReusableBuffer(OpenCLBridge.BufferAccess.READ_WRITE);
    private final ReusableBuffer heights = new ReusableBuffer(OpenCLBridge.BufferAccess.WRITE_ONLY);

    BatchBuffers prepare(OpenCLRuntime runtime, int[] parameterValues, long scratchBytes, long outputBytes) {
        return new BatchBuffers(
                params.write(runtime, parameterValues),
                scratch.allocate(runtime, scratchBytes),
                heights.allocate(runtime, outputBytes));
    }

    @Override
    public void close() {
        params.close();
        scratch.close();
        heights.close();
    }

    record BatchBuffers(OpenCLBridge.DeviceBuffer params,
                        OpenCLBridge.DeviceBuffer scratch,
                        OpenCLBridge.DeviceBuffer heights) {}

    private static final class ReusableBuffer implements AutoCloseable {
        private final OpenCLBridge.BufferAccess access;
        private OpenCLBridge.DeviceBuffer buffer;
        private long capacity;

        private ReusableBuffer(OpenCLBridge.BufferAccess access) {
            this.access = access;
        }

        private OpenCLBridge.DeviceBuffer write(OpenCLRuntime runtime, int[] values) {
            int[] safe = values == null || values.length == 0 ? new int[]{0} : values;
            OpenCLBridge.DeviceBuffer target = ensure(runtime,
                    Math.multiplyExact((long) safe.length, Integer.BYTES));
            runtime.writeInts(target, safe);
            return target;
        }

        private OpenCLBridge.DeviceBuffer allocate(OpenCLRuntime runtime, long bytes) {
            return ensure(runtime, bytes);
        }

        private OpenCLBridge.DeviceBuffer ensure(OpenCLRuntime runtime, long bytes) {
            long required = Math.max(1L, bytes);
            if (buffer == null || capacity < required) {
                if (buffer != null) {
                    buffer.close();
                }
                buffer = runtime.allocate(access, required);
                capacity = required;
            }
            return buffer;
        }

        @Override
        public void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
                capacity = 0L;
            }
        }
    }
}
