/* 文件职责：定义 Java density graph 与 OpenCL evaluator 共享的节点编号。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

/**
 * Density graph 节点类型。
 */
public enum DensityGraphNodeType {
    CONSTANT,
    ADD,
    MUL,
    MIN,
    MAX,
    CLAMP,
    ABS,
    SQUARE,
    CUBE,
    HALF_NEGATIVE,
    QUARTER_NEGATIVE,
    SQUEEZE,
    INVERT,
    Y_CLAMPED_GRADIENT,
    RANGE_CHOICE,
    NOISE,
    SHIFTED_NOISE,
    SHIFT_A,
    SHIFT_B,
    SHIFT,
    SPLINE,
    WEIRD_SCALED_SAMPLER,
    CLAMP_TO_NEAREST_UNIT,
    MARKER,
    INTERPOLATED,
    BLENDED_NOISE,
    END_ISLAND,
    AXIS,
    MIX,
    RANGE_CHOICE_INCLUSIVE,
    SIN,
    COS,
    FLOOR,
    CEIL,
    SQRT_SIGNED,
    CELLULAR_FAST_NOISE,
    TECTONIC_CONFIG_NOISE
}
