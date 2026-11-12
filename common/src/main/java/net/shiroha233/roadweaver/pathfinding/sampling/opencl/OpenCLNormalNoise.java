/* 文件职责：OpenCL 侧 NormalNoise 描述。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import java.util.List;

/**
 * OpenCL 侧 NormalNoise 描述。
 */
public record OpenCLNormalNoise(
        int firstPerlinIndex,
        int secondPerlinIndex,
        double valueFactor,
        double maxValue
) {}