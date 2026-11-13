/* 文件职责：保存精确道路寻路的步长、代价权重与搜索预算配置。 */
package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

/**
 * 寻路代价权重配置
 */
public final class PathfindingCostConfig implements SubConfig {

    /** 寻路算法枚举 */
    /**
 * 寻路算法。
 *
 * <p>{@link #BAND} 是 2026-11-12 新架构接入的第一个非 A* 算法：
 * Finsler 度量 + 快速行进 + 弹性带（见 {@code metric} / {@code topology} / {@code band}）。
 * 2026-11-13 起为唯一实现（旧 A* 系已全部删除）。</p>
 */
    public enum PathfindingAlgorithm {
        /** 新架构：Finsler 度量 + Fast Marching + 弹性带。 */
        BAND
    }

    private double orthoStepCost = RoadConstants.DEFAULT_ORTHO_STEP_COST;
    private double diagStepCost = RoadConstants.DEFAULT_DIAG_STEP_COST;
    private int elevationWeight = RoadConstants.DEFAULT_ELEVATION_WEIGHT;
    private int biomeWeight = RoadConstants.DEFAULT_BIOME_WEIGHT;
    private int stabilityWeight = RoadConstants.DEFAULT_STABILITY_WEIGHT;
    private int waterDepthWeight = RoadConstants.DEFAULT_WATER_DEPTH_WEIGHT;
    private int nearWaterCost = RoadConstants.DEFAULT_NEAR_WATER_COST;
    private int waterProximityCost = RoadConstants.DEFAULT_WATER_PROXIMITY_COST;
    private double heuristicWeight = RoadConstants.DEFAULT_HEURISTIC_WEIGHT;
    private double deviationWeight = RoadConstants.DEFAULT_DEVIATION_WEIGHT;
    private int aStarStep = RoadConstants.DEFAULT_ASTAR_STEP;
    private int aStarMaxSteps = RoadConstants.DEFAULT_ASTAR_MAX_STEPS;
    /**
     * 选路算法。
     *
     * <p>0.9.020 更正：这里原来是 {@code GRADIENT_DESCENT}，与生产配置和调参基线
     * （都是 {@code POTENTIAL_FIELD}）不一致 —— 后果是**新装 / 配置重置 / 缺这个键**
     * 时静默落进梯度下降，而 0.8.418「蜿蜒优先」那 20 多个旋钮
     * （{@code elevationSegmentedCost} / {@code contour*} / {@code deviation*} /
     * {@code biomeCost*} …）里有一大批**只被势场法读取** ⇒ 玩家调了半天一个都没生效，
     * 而且看不出原因。同一份配置在两套算法下含义不同，同一个种子换台机器形状会不一样。
     * 现在与生产、与 {@code RouteQualityHarness} 的调参基线统一。</p>
     */
    private PathfindingAlgorithm pathfindingAlgorithm = PathfindingAlgorithm.BAND;

    // ── POTENTIAL_FIELD（势场法）晋升常量（0.8.411 起可调）──────────────────
    // 原为 RoadConstants 硬编码，现晋升为可配置项，以便在势场法真实调参面上调优。
    private double contourDiscount = RoadConstants.DEFAULT_CONTOUR_DISCOUNT;
    private double softGradeLimit = RoadConstants.DEFAULT_SOFT_GRADE_LIMIT;
    private double hardGradeLimit = RoadConstants.DEFAULT_HARD_GRADE_LIMIT;
    private double softGradePenalty = RoadConstants.DEFAULT_SOFT_GRADE_PENALTY;
    private double hardGradePenalty = RoadConstants.DEFAULT_HARD_GRADE_PENALTY;
    private double gradientAlignPenalty = RoadConstants.DEFAULT_GRADIENT_ALIGN_PENALTY;
    private double pfWaterBasePenalty = RoadConstants.DEFAULT_PF_WATER_BASE_PENALTY;

    // ── 蜿蜒 / 群系走向（0.8.413 起可调）────────────────────────────────────
    // 逐群系类别路由代价（正=避让，负=偏好），配合 PathTerrainField.biomeCategory()。
    private double biomeCostWater = RoadConstants.DEFAULT_BIOME_COST_WATER;
    private double biomeCostPlains = RoadConstants.DEFAULT_BIOME_COST_PLAINS;
    private double biomeCostForest = RoadConstants.DEFAULT_BIOME_COST_FOREST;
    private double biomeCostMountain = RoadConstants.DEFAULT_BIOME_COST_MOUNTAIN;
    private double biomeCostSwamp = RoadConstants.DEFAULT_BIOME_COST_SWAMP;
    private double biomeCostDesert = RoadConstants.DEFAULT_BIOME_COST_DESERT;
    private double biomeCostOther = RoadConstants.DEFAULT_BIOME_COST_OTHER;
    // 等高线跟随强度：>1 放大柔和地形上的等高线折扣，产生更蜿蜒的走向。
    private double contourFollowWeight = RoadConstants.DEFAULT_CONTOUR_FOLLOW_WEIGHT;

    // ── 0.8.409 分段/软惩罚代价（最细粒度奖励惩罚体系）────────────────────────
    private int elevationGentleMax = RoadConstants.DEFAULT_ELEVATION_GENTLE_MAX;
    private int elevationGentleWeight = RoadConstants.DEFAULT_ELEVATION_GENTLE_WEIGHT;
    private int elevationSteepWeight = RoadConstants.DEFAULT_ELEVATION_STEEP_WEIGHT;
    private int elevationCliffThreshold = RoadConstants.DEFAULT_ELEVATION_CLIFF_THRESHOLD;
    private int elevationCliffWeight = RoadConstants.DEFAULT_ELEVATION_CLIFF_WEIGHT;
    private int deviationFreeRadius = RoadConstants.DEFAULT_DEVIATION_FREE_RADIUS;
    private boolean deviationSquared = RoadConstants.DEFAULT_DEVIATION_SQUARED;
    private int deviationHardCap = RoadConstants.DEFAULT_DEVIATION_HARD_CAP;
    private int nearWaterDecayRadius = RoadConstants.DEFAULT_NEAR_WATER_DECAY_RADIUS;
    private double nearWaterDecayRate = RoadConstants.DEFAULT_NEAR_WATER_DECAY_RATE;
    private int contourBonus = RoadConstants.DEFAULT_CONTOUR_BONUS;
    private int accumulatedSlopeWindow = RoadConstants.DEFAULT_ACCUMULATED_SLOPE_WINDOW;
    private int accumulatedSlopeWeight = RoadConstants.DEFAULT_ACCUMULATED_SLOPE_WEIGHT;

    // ── 0.8.409 麻花/重叠判定（禁麻花 + 允许丁字/十字）────────────────────────
    private double braidMinDirectionDot = RoadConstants.DEFAULT_BRAID_MIN_DIRECTION_DOT;
    private double braidNearParallelDot = RoadConstants.DEFAULT_BRAID_NEAR_PARALLEL_DOT;
    private boolean braidAllowJunctionCross = RoadConstants.DEFAULT_BRAID_ALLOW_JUNCTION_CROSS;
    private boolean braidAllowParallelOverlap = RoadConstants.DEFAULT_BRAID_ALLOW_PARALLEL_OVERLAP;

    // ── 0.9.018 D2 平行双桥「方案 C：后者整体改道」───────────────────────────
    /** 预检到并排重叠时是否让后一条路整体重算绕开。关掉即退回 0.9.017 行为。 */
    private boolean braidRerouteOnOverlap = RoadConstants.DEFAULT_BRAID_REROUTE_ON_OVERLAP;
    /** 改道时前一条路走廊两侧要留的净空格数。 */
    private int braidRerouteRadius = RoadConstants.DEFAULT_BRAID_REROUTE_RADIUS;
    /** 改道结果的长度上限比（相对原路的站数），超出即放弃改道。 */
    private double braidRerouteMaxLengthRatio = RoadConstants.DEFAULT_BRAID_REROUTE_MAX_LENGTH_RATIO;

    /**
     * 一次寻路的硬禁行走廊：<b>运行期瞬态，绝不落盘</b>。
     *
     * <p>用 {@code transient} 修饰是因为 {@code ConfigService} 用默认的
     * {@code GsonBuilder}（没有 {@code excludeFieldsWithModifiers}），GSON 默认就
     * 跳过 transient／static 字段，于是它不会被写进 {@code roadweaver.json}，
     * 也不会在加载时被反序列化回来。</p>
     *
     * <p>默认 {@code null} 是整套改道机制的安全底线：只要没人显式设它，
     * 所有寻路的热循环里那段判断就是一次空指针比较，代价与行为都等于改道之前。</p>
     */
    private transient net.shiroha233.roadweaver.pathfinding.impl.CorridorBan corridorBan;

    // ── 0.9.056 规划期走廊吸引（CorridorAttract，真机 (236,-170) 平行双桥）──────
    /**
     * 优惠走廊半径（格）：同一批里先算完的路，其中心线两侧这么大范围内的格子
     * 步进代价打折，后算的路会被吸过去共用一条桥。{@code 0} = 关闭本机制。
     */
    private int corridorAttractRadius = RoadConstants.DEFAULT_CORRIDOR_ATTRACT_RADIUS;

    /**
     * 优惠走廊的代价乘数：命中优惠带时<b>步进代价</b>乘这个系数（启发项绝不乘）。
     * {@code 1.0} = 关闭本机制，行为与没有吸引时逐位相同。
     */
    private double corridorAttractFactor = RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR;

    /**
     * 本次规划的优惠走廊：<b>运行期瞬态，绝不落盘</b>（与 {@code corridorBan} 同一套理由）。
     *
     * <p>默认 {@code null} 是安全底线：没人设它时，寻路热循环里那段判断就是一次空指针比较，
     * 代价与行为都等于本机制之前。</p>
     */
    private transient net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract corridorAttract;

    // ── 0.8.418 奖励惩罚补全（势场法真实生效的旋钮）──────────────────────────
    // 等高线折扣地板（原为硬编码 0.3）：调低 → 沿等高线走的步进代价可低至 baseCost×floor
    // （0.1 即 10 倍偏好），是增强「沿坡蜿蜒」的主杠杆；1.0 = 彻底关闭等高线偏好。
    private double contourFactorFloor = RoadConstants.DEFAULT_CONTOUR_FACTOR_FLOOR;
    // 竖向代价模型：true=0.8.409 三段台阶（默认，Q1 已接入）；false=旧二次式
    // （高度²×elevationWeight×0.5）。让 elevationWeight 在势场法下恢复可切换。
    private boolean elevationSegmentedCost = RoadConstants.DEFAULT_ELEVATION_COST_SEGMENTED;

    /**
     * 坡度罚的计费基准（0.9.028 / H-01 定性，0.9.062 / H-01 落点）。**默认 {@code true}**
     * （0.9.062 起；0.9.061 及以前是 {@code false}）。
     *
     * <p>{@code false} = 旧的「按步收」，与 0.9.061 逐位相同：罚额按 {@code grade = |ΔY| / 步长}
     * 的比率一次性结账，于是「每格超出免罚额度的竖向爬升」的单价是
     * {@code softGradePenalty / (softGradeLimit × 步长)} —— **与步长成反比**，正步比斜步贵约
     * 1.414 倍；而一个步骤的 baseCost 正比于长度。两者不是同一把尺子 ⇒「多走几个斜步把爬升
     * 摊薄」严格更优 ⇒ D1 锯齿 / 斜着横切。留着它的唯一用途是回退：
     * {@code -Droadweaver.slopePenaltyBasis=legacy} 或 {@code setSlopePenaltyByLength(false)}。
     *
     * <p>{@code true} = 按长度收：{@code max(0, rise - 上限 × 水平长度)} 以**竖向格数**计价，
     * 单价恒为 {@code softGradePenalty} / {@code hardGradePenalty}，与步长无关；免罚额度仍按
     * {@code softGradeLimit × 水平长度} 给（这一项本来就是对的）。
     * 推导与改前改后的实际数见 {@code PotentialFieldPathfinder#gradePenalty}。
     *
     * <p>出厂值只在字段初始化时读一次系统属性，因此 {@code sanitize()} 不会把它改回去，
     * 显式 {@code setSlopePenaltyByLength(false)} 也仍然说了算（回退路径）。
     */
    private boolean slopePenaltyByLength = initialSlopePenaltyByLength();

    /**
     * 0.9.062（②）：{@code slopePenaltyByLength} 的出厂值 + 运行时回退开关。
     *
     * <p>{@code legacy / per-step / false / 0 / off} 一律回到旧公式；其余（含空串/未设）都是
     * {@code true}。写在字段初始化而不是 {@code sanitize()} 里，是为了让
     * {@code setSlopePenaltyByLength(false)} + {@code withAStarStep(...)} 这条回退链不被清掉。</p>
     */
    private static boolean initialSlopePenaltyByLength() {
        String raw = System.getProperty(RoadConstants.SLOPE_PENALTY_BASIS_PROPERTY);
        if (raw == null) return true;
        String value = raw.trim();
        if (value.isEmpty()) return true;
        if (value.equalsIgnoreCase("legacy")) return false;
        if (value.equalsIgnoreCase("per-step")) return false;
        if (value.equalsIgnoreCase("false")) return false;
        if (value.equals("0")) return false;
        if (value.equalsIgnoreCase("off")) return false;
        return true;
    }

    /**
     * 航向连续性代价（0.9.030 / Z-2「爱平缓盘旋」）。
 * **注意：0.9.031 起 {@code headingFlipCost} 默认是 16（不是 0），所以「与旧行为逐位相同」已不成立。**
 * 而且这个 16 的**有效值是 48** —— 见 {@code HeadingContinuityCost}：三个寻路器把
 * {@code prevLegBlocks} 传成网格步长 {@code effectiveAStarStep()}（恒 4 或 8），
 * 而 {@code shortLegBlocks} 默认 48 ⇒ `d < 48` 恒真 ⇒ 短腿换向的 ×3 乘数对**每一次**换向生效。
 * 也就是说下面 "10×300 标定" 那组数字量的是 **48**，不是 16。
     *
     * <p>换向基础价（左-右-左横向速度变号）。与 {@code MountainSwitchbackPlanner.turnPenalty} 量级对齐。
     * 短腿换向（上一段 &lt; {@link #headingShortLegBlocks} 格）再乘 {@link #headingShortLegMultiplier}。
     * 同侧续弯（盘山长弧）只收 {@link #headingCurveCost}，绕远不设上限。
     */
    private double headingFlipCost = 16.0D;   // 0.9.031：标定翻默认。注意口径 —— 标定台是**合成地形**（RouteQualityHarness 的 9 个解析式原型，level/cache 传 null），
    // 绕过结构避让 / corridorBan / 群系代价 / 水体采样；且旧注释写「10×300」而代码里只有 9 个原型。
    // 实测 sharpTurns −10.5%、总转角 −22.5%，但**有效 flipCost 是 48 不是 16**（见上方说明）。
    private double headingCurveCost = 0.0D;   // 续弯降价保持 0（hf=8+hc=1 反而差）
    private double headingShortLegBlocks = 48.0D;
    private double headingShortLegMultiplier = 3.0D;

    @Override
    public void sanitize() {
        orthoStepCost = finiteNonNegative(orthoStepCost, RoadConstants.DEFAULT_ORTHO_STEP_COST);
        diagStepCost = finiteNonNegative(diagStepCost, RoadConstants.DEFAULT_DIAG_STEP_COST);
        elevationWeight = Math.max(0, elevationWeight);
        biomeWeight = Math.max(0, biomeWeight);
        stabilityWeight = Math.max(0, stabilityWeight);
        waterDepthWeight = Math.max(0, waterDepthWeight);
        nearWaterCost = Math.max(0, nearWaterCost);
        waterProximityCost = Math.max(0, waterProximityCost);
        heuristicWeight = finiteNonNegative(heuristicWeight, RoadConstants.DEFAULT_HEURISTIC_WEIGHT);
        deviationWeight = finiteNonNegative(deviationWeight, RoadConstants.DEFAULT_DEVIATION_WEIGHT);
        aStarStep = Math.max(RoadConstants.ASTAR_STEP_MIN,
                Math.min(RoadConstants.ASTAR_STEP_MAX, aStarStep));
        aStarMaxSteps = Math.max(RoadConstants.ASTAR_MAX_STEPS_MIN,
                Math.min(RoadConstants.ASTAR_MAX_STEPS_MAX, aStarMaxSteps));
        if (pathfindingAlgorithm == null) pathfindingAlgorithm = PathfindingAlgorithm.BAND;
        // 2026-11-13：旧 A* 系寻路器已全部删除，枚举只剩 BAND 一种。
        // 老配置里写着的旧枚举名（ASTAR_BASIC 等）会被 GSON 当成未知值返回 null，
        // 这里统一回落到 BAND。
        // POTENTIAL_FIELD 晋升常量：折扣/阈值落 [0,1]，惩罚非负；硬阈值不得低于软阈值（单调）。
        contourDiscount = finiteFraction(contourDiscount, RoadConstants.DEFAULT_CONTOUR_DISCOUNT);
        softGradeLimit = finiteFraction(softGradeLimit, RoadConstants.DEFAULT_SOFT_GRADE_LIMIT);
        hardGradeLimit = finiteFraction(hardGradeLimit, RoadConstants.DEFAULT_HARD_GRADE_LIMIT);
        if (hardGradeLimit < softGradeLimit) hardGradeLimit = softGradeLimit;
        softGradePenalty = finiteNonNegative(softGradePenalty, RoadConstants.DEFAULT_SOFT_GRADE_PENALTY);
        hardGradePenalty = finiteNonNegative(hardGradePenalty, RoadConstants.DEFAULT_HARD_GRADE_PENALTY);
        gradientAlignPenalty = finiteNonNegative(gradientAlignPenalty, RoadConstants.DEFAULT_GRADIENT_ALIGN_PENALTY);
        pfWaterBasePenalty = finiteNonNegative(pfWaterBasePenalty, RoadConstants.DEFAULT_PF_WATER_BASE_PENALTY);
        // 蜿蜒/群系走向：代价允许负数（=偏好），仅保证有限；等高线强度非负。
        biomeCostWater = finiteSigned(biomeCostWater, RoadConstants.DEFAULT_BIOME_COST_WATER);
        biomeCostPlains = finiteSigned(biomeCostPlains, RoadConstants.DEFAULT_BIOME_COST_PLAINS);
        biomeCostForest = finiteSigned(biomeCostForest, RoadConstants.DEFAULT_BIOME_COST_FOREST);
        biomeCostMountain = finiteSigned(biomeCostMountain, RoadConstants.DEFAULT_BIOME_COST_MOUNTAIN);
        biomeCostSwamp = finiteSigned(biomeCostSwamp, RoadConstants.DEFAULT_BIOME_COST_SWAMP);
        biomeCostDesert = finiteSigned(biomeCostDesert, RoadConstants.DEFAULT_BIOME_COST_DESERT);
        biomeCostOther = finiteSigned(biomeCostOther, RoadConstants.DEFAULT_BIOME_COST_OTHER);
        contourFollowWeight = finiteNonNegative(contourFollowWeight, RoadConstants.DEFAULT_CONTOUR_FOLLOW_WEIGHT);
        // 0.8.409 分段代价：钳制到安全区间，保证单调性（gentleMax < cliffThreshold）与几何一致。
        elevationGentleMax = Math.max(0, Math.min(16, elevationGentleMax));
        elevationGentleWeight = Math.max(0, elevationGentleWeight);
        elevationSteepWeight = Math.max(0, elevationSteepWeight);
        elevationCliffThreshold = Math.max(elevationGentleMax + 1, Math.min(32, elevationCliffThreshold));
        elevationCliffWeight = Math.max(0, elevationCliffWeight);
        deviationFreeRadius = Math.max(0, Math.min(2048, deviationFreeRadius));
        deviationHardCap = Math.max(deviationFreeRadius, Math.min(8192, deviationHardCap));
        nearWaterDecayRadius = Math.max(1, Math.min(64, nearWaterDecayRadius));
        nearWaterDecayRate = finiteFraction(nearWaterDecayRate, RoadConstants.DEFAULT_NEAR_WATER_DECAY_RATE);
        contourBonus = Math.min(0, contourBonus);
        accumulatedSlopeWindow = Math.max(2, Math.min(16, accumulatedSlopeWindow));
        accumulatedSlopeWeight = Math.max(0, accumulatedSlopeWeight);
        // 0.8.418：等高线地板落 [0.02,1]（1 = 关闭折扣）。
        contourFactorFloor = finiteFraction(contourFactorFloor, RoadConstants.DEFAULT_CONTOUR_FACTOR_FLOOR);
        if (contourFactorFloor < 0.02D) contourFactorFloor = 0.02D;
        // 麻花：保证 minDirectionDot ≤ nearParallelDot，且方向点积落在 [0,1]。
        braidMinDirectionDot = finiteFraction(braidMinDirectionDot, RoadConstants.DEFAULT_BRAID_MIN_DIRECTION_DOT);
        braidNearParallelDot = finiteFraction(braidNearParallelDot, RoadConstants.DEFAULT_BRAID_NEAR_PARALLEL_DOT);
        if (braidNearParallelDot < braidMinDirectionDot) {
            braidNearParallelDot = braidMinDirectionDot;
        }
        // 0.9.018：改道净空取 [4,64]（4 格等于一次 A* 步长的一半，再小就没有约束力；
        // 64 格已经远超任何有意义的走廊宽度，再大只会让 A* 无解）。
        braidRerouteRadius = Math.max(4, Math.min(64, braidRerouteRadius));
        if (!Double.isFinite(braidRerouteMaxLengthRatio)) {
            braidRerouteMaxLengthRatio = RoadConstants.DEFAULT_BRAID_REROUTE_MAX_LENGTH_RATIO;
        }
        // 下限 1.05：低于它等于「一改道就判绕太远」，机制会静默失效。
        braidRerouteMaxLengthRatio = Math.max(1.05D, Math.min(4.0D, braidRerouteMaxLengthRatio));
        // 0.9.056：优惠走廊半径取 [0,64]（0 = 关闭），系数取 [0.05,1.0]（1.0 = 关闭）。
        // 上限 64 与改道净空同口径：再大就等于「整片区域都打折」，路会直接走直线。
        corridorAttractRadius = Math.max(0, Math.min(64, corridorAttractRadius));
        corridorAttractFactor = finiteFraction(corridorAttractFactor,
                RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR);
        if (corridorAttractFactor < 0.05D) corridorAttractFactor = 0.05D;
    }

    private static double finiteFraction(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : fallback;
    }

    private static double finiteNonNegative(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : fallback;
    }

    private static double finiteSigned(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    @Override
    public PathfindingCostConfig snapshot() {
        PathfindingCostConfig copy = new PathfindingCostConfig();
        copy.orthoStepCost = this.orthoStepCost;
        copy.diagStepCost = this.diagStepCost;
        copy.elevationWeight = this.elevationWeight;
        copy.biomeWeight = this.biomeWeight;
        copy.stabilityWeight = this.stabilityWeight;
        copy.waterDepthWeight = this.waterDepthWeight;
        copy.nearWaterCost = this.nearWaterCost;
        copy.waterProximityCost = this.waterProximityCost;
        copy.heuristicWeight = this.heuristicWeight;
        copy.deviationWeight = this.deviationWeight;
        copy.aStarStep = this.aStarStep;
        copy.aStarMaxSteps = this.aStarMaxSteps;
        copy.pathfindingAlgorithm = this.pathfindingAlgorithm;
        copy.contourDiscount = this.contourDiscount;
        copy.softGradeLimit = this.softGradeLimit;
        copy.hardGradeLimit = this.hardGradeLimit;
        copy.softGradePenalty = this.softGradePenalty;
        copy.hardGradePenalty = this.hardGradePenalty;
        copy.gradientAlignPenalty = this.gradientAlignPenalty;
        copy.pfWaterBasePenalty = this.pfWaterBasePenalty;
        copy.biomeCostWater = this.biomeCostWater;
        copy.biomeCostPlains = this.biomeCostPlains;
        copy.biomeCostForest = this.biomeCostForest;
        copy.biomeCostMountain = this.biomeCostMountain;
        copy.biomeCostSwamp = this.biomeCostSwamp;
        copy.biomeCostDesert = this.biomeCostDesert;
        copy.biomeCostOther = this.biomeCostOther;
        copy.contourFollowWeight = this.contourFollowWeight;
        copy.elevationGentleMax = this.elevationGentleMax;
        copy.elevationGentleWeight = this.elevationGentleWeight;
        copy.elevationSteepWeight = this.elevationSteepWeight;
        copy.elevationCliffThreshold = this.elevationCliffThreshold;
        copy.elevationCliffWeight = this.elevationCliffWeight;
        copy.deviationFreeRadius = this.deviationFreeRadius;
        copy.deviationSquared = this.deviationSquared;
        copy.deviationHardCap = this.deviationHardCap;
        copy.nearWaterDecayRadius = this.nearWaterDecayRadius;
        copy.nearWaterDecayRate = this.nearWaterDecayRate;
        copy.contourBonus = this.contourBonus;
        copy.accumulatedSlopeWindow = this.accumulatedSlopeWindow;
        copy.accumulatedSlopeWeight = this.accumulatedSlopeWeight;
        copy.braidMinDirectionDot = this.braidMinDirectionDot;
        copy.braidNearParallelDot = this.braidNearParallelDot;
        copy.braidAllowJunctionCross = this.braidAllowJunctionCross;
        copy.braidAllowParallelOverlap = this.braidAllowParallelOverlap;
        copy.contourFactorFloor = this.contourFactorFloor;
        copy.elevationSegmentedCost = this.elevationSegmentedCost;
        copy.slopePenaltyByLength = this.slopePenaltyByLength;
        copy.headingFlipCost = this.headingFlipCost;
        copy.headingCurveCost = this.headingCurveCost;
        copy.headingShortLegBlocks = this.headingShortLegBlocks;
        copy.headingShortLegMultiplier = this.headingShortLegMultiplier;
        // 0.9.018：改道旋钮 + 瞬态禁行走廊一并带进快照。
        // 走廊必须跟着 snapshot 走：RoadTerrainPlanningPort.Request 的紧凑构造器
        // 会再 snapshot() 一次，漏拷就等于禁令在到达寻路器之前被悄悄丢掉。
        copy.braidRerouteOnOverlap = this.braidRerouteOnOverlap;
        copy.braidRerouteRadius = this.braidRerouteRadius;
        copy.braidRerouteMaxLengthRatio = this.braidRerouteMaxLengthRatio;
        // 0.9.056：优惠走廊旋钮 + 瞬态吸引器一并带进快照（同 corridorBan：漏拷就等于
        // 吸引力在到达寻路器之前被悄悄丢掉，因为 Request 的紧凑构造器会再 snapshot 一次）。
        copy.corridorAttractRadius = this.corridorAttractRadius;
        copy.corridorAttractFactor = this.corridorAttractFactor;
        copy.corridorAttract = this.corridorAttract != null
                ? this.corridorAttract
                : net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract.current();
        // 禁令的取值点就在这里：本次请求显式设过就用显式的，否则取当前线程挂的
        // 那条（D2 改道重算的整体作用域）。两次寻路之间互不影响，也不必改任何签名。
        copy.corridorBan = this.corridorBan != null
                ? this.corridorBan
                : net.shiroha233.roadweaver.pathfinding.impl.CorridorBan.current();
        return copy;
    }

    public double orthoStepCost() { return orthoStepCost; }
    public void setOrthoStepCost(double v) { this.orthoStepCost = v; }
    public double diagStepCost() { return diagStepCost; }
    public void setDiagStepCost(double v) { this.diagStepCost = v; }
    public int elevationWeight() { return elevationWeight; }
    public void setElevationWeight(int v) { this.elevationWeight = v; }
    public int biomeWeight() { return biomeWeight; }
    public void setBiomeWeight(int v) { this.biomeWeight = v; }
    public int stabilityWeight() { return stabilityWeight; }
    public void setStabilityWeight(int v) { this.stabilityWeight = v; }
    public int waterDepthWeight() { return waterDepthWeight; }
    public void setWaterDepthWeight(int v) { this.waterDepthWeight = v; }
    public int nearWaterCost() { return nearWaterCost; }
    public void setNearWaterCost(int v) { this.nearWaterCost = v; }
    public int waterProximityCost() { return waterProximityCost; }
    public void setWaterProximityCost(int v) { this.waterProximityCost = v; }
    public double heuristicWeight() { return heuristicWeight; }
    public void setHeuristicWeight(double v) { this.heuristicWeight = v; }
    public double deviationWeight() { return deviationWeight; }
    public void setDeviationWeight(double v) { this.deviationWeight = v; }
    public int aStarStep() { return aStarStep; }
    public void setAStarStep(int v) { this.aStarStep = v; }
    public int aStarMaxSteps() { return aStarMaxSteps; }
    public void setAStarMaxSteps(int v) { this.aStarMaxSteps = v; }
    public PathfindingAlgorithm pathfindingAlgorithm() { return pathfindingAlgorithm; }
    public void setPathfindingAlgorithm(PathfindingAlgorithm v) { this.pathfindingAlgorithm = v; }
    public PathfindingAlgorithm algorithm() { return pathfindingAlgorithm; }
    public void setAlgorithm(PathfindingAlgorithm v) { this.pathfindingAlgorithm = v; }

    // ── POTENTIAL_FIELD（势场法）晋升常量 getter/setter ──────────────────────
    public double contourDiscount() { return contourDiscount; }
    public void setContourDiscount(double v) { this.contourDiscount = v; }
    public double softGradeLimit() { return softGradeLimit; }
    public void setSoftGradeLimit(double v) { this.softGradeLimit = v; }
    public double hardGradeLimit() { return hardGradeLimit; }
    public void setHardGradeLimit(double v) { this.hardGradeLimit = v; }
    public double softGradePenalty() { return softGradePenalty; }
    public void setSoftGradePenalty(double v) { this.softGradePenalty = v; }
    public double hardGradePenalty() { return hardGradePenalty; }
    public void setHardGradePenalty(double v) { this.hardGradePenalty = v; }
    public double gradientAlignPenalty() { return gradientAlignPenalty; }
    public void setGradientAlignPenalty(double v) { this.gradientAlignPenalty = v; }
    public double pfWaterBasePenalty() { return pfWaterBasePenalty; }
    public void setPfWaterBasePenalty(double v) { this.pfWaterBasePenalty = v; }

    // ── 蜿蜒 / 群系走向 getter/setter ────────────────────────────────────────
    public double biomeCostWater() { return biomeCostWater; }
    public void setBiomeCostWater(double v) { this.biomeCostWater = v; }
    public double biomeCostPlains() { return biomeCostPlains; }
    public void setBiomeCostPlains(double v) { this.biomeCostPlains = v; }
    public double biomeCostForest() { return biomeCostForest; }
    public void setBiomeCostForest(double v) { this.biomeCostForest = v; }
    public double biomeCostMountain() { return biomeCostMountain; }
    public void setBiomeCostMountain(double v) { this.biomeCostMountain = v; }
    public double biomeCostSwamp() { return biomeCostSwamp; }
    public void setBiomeCostSwamp(double v) { this.biomeCostSwamp = v; }
    public double biomeCostDesert() { return biomeCostDesert; }
    public void setBiomeCostDesert(double v) { this.biomeCostDesert = v; }
    public double biomeCostOther() { return biomeCostOther; }
    public void setBiomeCostOther(double v) { this.biomeCostOther = v; }
    public double contourFollowWeight() { return contourFollowWeight; }
    public void setContourFollowWeight(double v) { this.contourFollowWeight = v; }
    public double contourFactorFloor() { return contourFactorFloor; }
    public void setContourFactorFloor(double v) { this.contourFactorFloor = v; }
    public boolean elevationSegmentedCost() { return elevationSegmentedCost; }
    /** 0.9.028 / H-01：坡度罚改按「超出的竖向格数」计价（默认 false = 旧行为逐位相同）。 */
    public boolean slopePenaltyByLength() { return slopePenaltyByLength; }
    public void setSlopePenaltyByLength(boolean v) { this.slopePenaltyByLength = v; }

    /** 0.9.030 / Z-2：航向连续性代价。默认全 0 ⇒ 整族关闭（与旧行为逐位相同）。 */
    public double headingFlipCost() { return headingFlipCost; }
    public void setHeadingFlipCost(double v) { this.headingFlipCost = Math.max(0.0D, v); }
    public double headingCurveCost() { return headingCurveCost; }
    public void setHeadingCurveCost(double v) { this.headingCurveCost = Math.max(0.0D, v); }
    public double headingShortLegBlocks() { return headingShortLegBlocks; }
    public void setHeadingShortLegBlocks(double v) { this.headingShortLegBlocks = Math.max(1.0D, v); }
    public double headingShortLegMultiplier() { return headingShortLegMultiplier; }
    public void setHeadingShortLegMultiplier(double v) { this.headingShortLegMultiplier = Math.max(1.0D, v); }
    public void setElevationSegmentedCost(boolean v) { this.elevationSegmentedCost = v; }

    /** 按群系路由类别返回基代价（正=避让，负=偏好）；最终再乘 biomeWeight。 */
    public double biomeCostFor(PathTerrainField.BiomeCategory category) {
        if (category == null) return biomeCostOther;
        return switch (category) {
            case WATER -> biomeCostWater;
            case PLAINS -> biomeCostPlains;
            case FOREST -> biomeCostForest;
            case MOUNTAIN -> biomeCostMountain;
            case SWAMP -> biomeCostSwamp;
            case DESERT -> biomeCostDesert;
            case OTHER -> biomeCostOther;
        };
    }

    // ── 0.8.409 分段/软惩罚 getter/setter ──────────────────────────────────────
    public int elevationGentleMax() { return elevationGentleMax; }
    public void setElevationGentleMax(int v) { this.elevationGentleMax = v; }
    public int elevationGentleWeight() { return elevationGentleWeight; }
    public void setElevationGentleWeight(int v) { this.elevationGentleWeight = v; }
    public int elevationSteepWeight() { return elevationSteepWeight; }
    public void setElevationSteepWeight(int v) { this.elevationSteepWeight = v; }
    public int elevationCliffThreshold() { return elevationCliffThreshold; }
    public void setElevationCliffThreshold(int v) { this.elevationCliffThreshold = v; }
    public int elevationCliffWeight() { return elevationCliffWeight; }
    public void setElevationCliffWeight(int v) { this.elevationCliffWeight = v; }
    public int deviationFreeRadius() { return deviationFreeRadius; }
    public void setDeviationFreeRadius(int v) { this.deviationFreeRadius = v; }
    public boolean deviationSquared() { return deviationSquared; }
    public void setDeviationSquared(boolean v) { this.deviationSquared = v; }
    public int deviationHardCap() { return deviationHardCap; }
    public void setDeviationHardCap(int v) { this.deviationHardCap = v; }
    public int nearWaterDecayRadius() { return nearWaterDecayRadius; }
    public void setNearWaterDecayRadius(int v) { this.nearWaterDecayRadius = v; }
    public double nearWaterDecayRate() { return nearWaterDecayRate; }
    public void setNearWaterDecayRate(double v) { this.nearWaterDecayRate = v; }
    public int contourBonus() { return contourBonus; }
    public void setContourBonus(int v) { this.contourBonus = v; }
    public int accumulatedSlopeWindow() { return accumulatedSlopeWindow; }
    public void setAccumulatedSlopeWindow(int v) { this.accumulatedSlopeWindow = v; }
    public int accumulatedSlopeWeight() { return accumulatedSlopeWeight; }
    public void setAccumulatedSlopeWeight(int v) { this.accumulatedSlopeWeight = v; }
    public double braidMinDirectionDot() { return braidMinDirectionDot; }
    public void setBraidMinDirectionDot(double v) { this.braidMinDirectionDot = v; }
    public double braidNearParallelDot() { return braidNearParallelDot; }
    public void setBraidNearParallelDot(double v) { this.braidNearParallelDot = v; }
    public boolean braidAllowJunctionCross() { return braidAllowJunctionCross; }
    public void setBraidAllowJunctionCross(boolean v) { this.braidAllowJunctionCross = v; }
    public boolean braidAllowParallelOverlap() { return braidAllowParallelOverlap; }
    public void setBraidAllowParallelOverlap(boolean v) { this.braidAllowParallelOverlap = v; }

    // ── 0.9.018 D2 平行双桥「方案 C：后者整体改道」───────────────────────────
    public boolean braidRerouteOnOverlap() { return braidRerouteOnOverlap; }
    public void setBraidRerouteOnOverlap(boolean v) { this.braidRerouteOnOverlap = v; }
    public int braidRerouteRadius() { return braidRerouteRadius; }
    public void setBraidRerouteRadius(int v) { this.braidRerouteRadius = v; }
    public double braidRerouteMaxLengthRatio() { return braidRerouteMaxLengthRatio; }
    public void setBraidRerouteMaxLengthRatio(double v) { this.braidRerouteMaxLengthRatio = v; }

    /**
     * 本次寻路的硬禁行走廊，{@code null} 表示无禁令（默认）。
     *
     * <p>瞬态字段：不进 JSON、不参与 sanitize、只在一次改道重算的生命周期里存在。</p>
     */
    public net.shiroha233.roadweaver.pathfinding.impl.CorridorBan corridorBan() { return corridorBan; }

    public void setCorridorBan(net.shiroha233.roadweaver.pathfinding.impl.CorridorBan v) {
        this.corridorBan = v;
    }

    // ── 0.9.056 规划期走廊吸引（CorridorAttract）──────────────────────────────
    /** 优惠走廊半径（格）。{@code 0} = 关闭本机制。 */
    public int corridorAttractRadius() { return corridorAttractRadius; }
    public void setCorridorAttractRadius(int v) { this.corridorAttractRadius = v; }

    /** 优惠走廊的步进代价乘数。{@code 1.0} = 关闭本机制（逐位退回旧行为）。 */
    public double corridorAttractFactor() { return corridorAttractFactor; }
    public void setCorridorAttractFactor(double v) { this.corridorAttractFactor = v; }

    /**
     * 本次规划的优惠走廊，{@code null} 表示无吸引（默认）。
     *
     * <p>瞬态字段：不进 JSON、不参与 sanitize、只在一次批内规划的生命周期里存在。</p>
     */
    public net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract corridorAttract() {
        return corridorAttract;
    }

    public void setCorridorAttract(net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract v) {
        this.corridorAttract = v;
    }

    public int effectiveAStarStep() {
        if (aStarStep < RoadConstants.ASTAR_STEP_MIN) return RoadConstants.ASTAR_STEP_MIN;
        if (aStarStep > RoadConstants.ASTAR_STEP_MAX) return RoadConstants.ASTAR_STEP_MAX;
        return aStarStep;
    }

    /** Returns an isolated planning snapshot at a temporary precision. */
    public PathfindingCostConfig withAStarStep(int step) {
        PathfindingCostConfig copy = snapshot();
        copy.aStarStep = step;
        copy.sanitize();
        return copy;
    }
}
