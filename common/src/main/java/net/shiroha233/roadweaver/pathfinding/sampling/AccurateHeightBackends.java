/* 文件职责：按配置与世界生成器选择精确高度采样后端。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.server.level.ServerLevel;

/**
 * 精确高度后端工厂。
 */
public final class AccurateHeightBackends {
    private AccurateHeightBackends() {}

    public static AccurateHeightBackend create(ServerLevel level, CpuAccurateHeightBackend cpu) {
        // Precise height sampling is intentionally CPU-only. Dense mod-pack
        // worldgen produces a huge accurate density graph whose per-chunk
        // OpenCL buffer exceeds the 4060's single-buffer limit (2046 MiB), so
        // every GPU request fails with clCreateBuffer -61 and falls back to CPU
        // anyway. Going straight to CPU avoids the wasted GPU attempts and the
        // validation-mismatch fallback that stalled route generation.
        return cpu;
    }
}
