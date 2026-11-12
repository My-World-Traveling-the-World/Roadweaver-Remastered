/* 文件职责：确定性合成地形（离线寻路质量测量用），纯 (seed,x,z) 函数，无 Minecraft 世界生成依赖。 */
package net.shiroha233.roadweaver.tuning;

import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

/**
 * 合成地形数据模型：10 种「地形原型」之一 + 一条南北向蜿蜒河流 + 一个偏置湖泊。
 *
 * <p><b>为什么要原型</b>：道路寻路的坡度代价分档（分段坡度代价的缓坡 ≤2 / 中坡 3~5 / 陡坡 &gt;5
 * 格每步）只有在<b>坡度谱足够宽</b>的地形上才有区分度。原先单一「缓丘」原型坡度只有 8%~14%
 * （每步高度变化 0~1 格），三档里只有缓坡档被触发过，调参自然扫不出结论。
 *
 * <p>每种原型由种子派生各自的屏障高度/半宽/缺口位置等参数，使<b>坡度谱覆盖约 2%~340%</b>
 * （每步高度变化 0~13 格），把三档全部打满。含屏障的原型都让屏障横贯全图（南北向，x≈512），
 * 主线（64,64）→（960,960）必须横穿，因此路径被<b>强迫爬坡</b>而不是绕开。
 *
 * <p>布局（河流/湖泊位置）固定，丘陵与屏障相位由种子派生，保证：
 * <ul>
 *   <li>确定性：同一 {@code (archetype, seed)} 生成完全相同的地形；</li>
 *   <li>泛化性：不同种子形态不同，用于考卷种子检验过拟合。</li>
 * </ul>
 *
 * <p>水体语义对齐 Minecraft：水柱的 {@link #surfaceY(int, int)} 返回海平面（水面），
 * {@link #floorY(int, int)} 返回水下地面，{@link #waterDepth(int, int)} = 海平面 - 地面。
 *
 * <p>兼容性：{@link #SyntheticTerrain(long)} 等价于 {@link Archetype#GENTLE_HILLS}，且所有
 * 原型共用的种子参数派生顺序不变（新参数一律追加在末尾），因此<b>旧种子产出与旧版逐位相同</b>。
 */
public final class SyntheticTerrain implements RouteQualityMetrics.WaterProbe {

    /** 地形原型。 */
    public enum Archetype {
        /** 极缓平原：最大坡度 ~2%~4%，全缓坡对照组。 */
        PLAINS_FLAT,
        /** 缓丘（原始基线）：坡度 8%~14%，每步 0~2 格。 */
        GENTLE_HILLS,
        /** 陡丘：短波长大振幅，坡度 ~55%~140%，每步 2~6 格。 */
        STEEP_HILLS,
        /** 高斯山脊屏障：坡度 ~21%~343%，每步 1~14 格。 */
        MOUNTAIN_PASS,
        /** 台地 + 垂直崖面（不可翻越）+ 一道缺口（缺口坡度随种子 0.2~3.7）。 */
        CLIFF_PLATEAU,
        /** 深谷屏障：先下后上，谷壁坡度 ~21%~343%。 */
        CANYON,
        /** 三角形刃脊：恒定坡度 = 高度 / 半宽（~31%~400%）。 */
        RIDGE_CROSS,
        /** 多层台地：3~5 道崖面台阶（每道坡度 ~94%~1080%）。 */
        MESA_TERRACES,
        /** 火山锥：恒定坡度 = 高度 / 半径（~35%~433%）+ 火口，锥心落在主线上。 */
        VOLCANO,
        /** 混合：一道山脊 + 一道深谷，主线先后穿越两种陡度。 */
        MIXED
    }

    public static final int WORLD_MIN_X = 0;
    public static final int WORLD_MIN_Z = 0;
    public static final int WORLD_MAX_X = 1024;
    public static final int WORLD_MAX_Z = 1024;
    public static final int SEA_LEVEL = 64;

    // 河流：南北向 cosine 河谷（中心最深、边缘零斜率），横跨整张图（必须跨过的障碍）。
    private static final int RIVER_X = 512;
    private static final int RIVER_VALLEY_DEPTH = 20;        // 河心挖深（河心≈56，水深 8）
    // 河谷半宽由种子派生（100~260），见字段 riverValleyHalfWidth——使「近水占比」随种子变化。

    // 湖泊：偏离对角线（z=x）的圆形 cosine 湖盆（当前挪到边角，不干扰主线，后续再启用）。
    private static final int LAKE_X = 150;
    private static final int LAKE_Z = 400;
    private static final int LAKE_VALLEY_RADIUS = 60;    // 湖盆半径
    private static final int LAKE_VALLEY_DEPTH = 12;     // 湖心挖深

    // 蜿蜒河流：河心线随 z 正弦摆动（麻花状），迫使跨河路径顺势弯曲。
    private static final double MEANDER_AMPLITUDE = 48.0;   // 河心左右摆幅（块）
    private static final double MEANDER_FREQ = 0.012272;    // 波长 ~512（与丘陵主波一致）

    // 群系分区（「群系走向」调参用）：山地=高峰带、沼泽=近水带，森林/沙漠=确定性掩码带。
    private static final int MOUNTAIN_ELEVATION = 40;       // 地表 ≥ SEA_LEVEL+40 视为山地
    private static final int SWAMP_BAND = 24;               // 距水 ≤24 且非水视为沼泽

    // 森林斑块：一个**局部化**的独立群系障碍（圆形，非水、非高海拔），横在起终点主线附近，
    // 用于验证 biomeCostForest 能否迫使路线「绕行 vs 直穿」（唯一代价是群系代价，与海拔/水体无关）。
    private static final int FOREST_PATCH_X = 750;
    private static final int FOREST_PATCH_Z = 750;
    private static final int FOREST_PATCH_RADIUS = 120;

    // 起终点锚定（避免起终点落水导致指标失真）。
    private static final int START_X = 64;
    private static final int START_Z = 64;
    private static final int END_X = 960;
    private static final int END_Z = 960;
    private static final int ANCHOR_RADIUS = 24;
    /** 锚点抬升的平滑过渡外半径（CORE..BLEND 之间线性平滑到地形原高，避免人造悬崖）。 */
    private static final int ANCHOR_BLEND_RADIUS = 64;

    private static final double TAU = Math.PI * 2.0;

    private final long seed;
    private final Archetype archetype;
    private final double phase1;
    private final double phase2;
    private final double phase3;
    private final double phase4;
    private final double phase5;
    // 种子派生的地形画像参数（决定水占比/起伏/山体占比，用于「画像→参数」学习）。
    private final double baseHeight;    // 70~84（低=略湿，高=干燥；全部高于海平面，水只来自河谷/峡谷/湖）
    private final double hillAmp1;      // 3~14（丘陵主振幅）
    private final double hillAmp2;      // 2~8
    private final double hillAmp3;      // 1~5
    private final double mountainAmp;   // 0~22（单峰山脊峰值，0=无山）
    private final double mountainCx;    // 山脊中心 X（0~WORLD_MAX_X）
    private final double mountainCz;    // 山脊中心 Z（0~WORLD_MAX_Z）
    private final double mountainSigma; // 山脊高斯半宽 90~240
    private final double riverValleyHalfWidth; // 河谷半宽 100~260（决定「近水占比」画像）

    // ---- 以下为「地形原型」专用参数（追加在原有派生顺序之后，保证旧种子取值不变）----
    // 坡度谱设计：每步高度变化 = 坡度 × A* 步长 4，需要同时出现 ≤2 / 3~5 / >5 三种情形。
    private final double barrierAmp;     // 50~220：山脊/台地/峡谷/刃脊/火山的高度
    private final double barrierSigma;   // 55~200：屏障半宽（高斯 sigma）
    private final double barrierCenterX; // 440~584：南北向屏障中心 X（主线必经）
    private final double barrierMeander; // 0~96：屏障蜿蜒幅度
    private final double barrierPhase;   // 屏障蜿蜒相位
    private final double passZ;          // 120~920：缺口/通道位置 Z
    private final double passWidth;      // 60~240：缺口半宽（决定缺口坡度 = barrierAmp / passWidth）
    private final double steepAmp1;      // 16~42：陡丘主振幅（×0.033 → 坡度 53%~139%）
    private final double steepAmp2;      // 10~28
    private final double steepAmp3;      // 5~16
    private final int bandCount;         // 3~5：多层台地的台阶数
    private final double bandWidth;      // 3~12：崖面水平宽度（块）
    private final double bandHeight;     // 12~45：每层台阶高度
    private final double volcanoCx;      // 火山中心 X（落在主线 z=x 上）
    private final double volcanoCz;      // 火山中心 Z（落在主线 z=x 上）
    private final double volcanoRadius;  // 60~200：火山半径
    private final double volcanoAmp;     // 70~260：火山高度 ⇒ 恒定坡度 = amp/radius（35%~433%）

    /** 地面高度记忆化缓存：0 = 未计算（groundY 恒 &gt; 0，下限约 34）。 */
    private final int[] groundCache = new int[(WORLD_MAX_X + 1) * (WORLD_MAX_Z + 1)];

    /** 缓丘基线（等价于旧版唯一地形）。 */
    public SyntheticTerrain(long seed) {
        this(seed, Archetype.GENTLE_HILLS);
    }

    public SyntheticTerrain(long seed, Archetype archetype) {
        this.seed = seed;
        this.archetype = archetype == null ? Archetype.GENTLE_HILLS : archetype;
        long m = mix(seed ^ 0x9e3779b97f4a7c15L);
        this.phase1 = frac01(m) * TAU;
        m = mix(m); this.phase2 = frac01(m) * TAU;
        m = mix(m); this.phase3 = frac01(m) * TAU;
        m = mix(m); this.phase4 = frac01(m) * TAU;
        m = mix(m); this.phase5 = frac01(m) * TAU;
        m = mix(m); this.baseHeight = 70.0 + frac01(m) * 14.0;
        m = mix(m); this.hillAmp1 = 3.0 + frac01(m) * 11.0;
        m = mix(m); this.hillAmp2 = 2.0 + frac01(m) * 6.0;
        m = mix(m); this.hillAmp3 = 1.0 + frac01(m) * 4.0;
        m = mix(m); this.mountainAmp = frac01(m) * 22.0;
        m = mix(m); this.mountainCx = frac01(m) * WORLD_MAX_X;
        m = mix(m); this.mountainCz = frac01(m) * WORLD_MAX_Z;
        m = mix(m); this.mountainSigma = 90.0 + frac01(m) * 150.0;
        m = mix(m); this.riverValleyHalfWidth = 100.0 + frac01(m) * 160.0;
        // ---- 原型参数（顺序追加，不改动上面已有取值）----
        m = mix(m); this.barrierAmp = 50.0 + frac01(m) * 170.0;
        m = mix(m); this.barrierSigma = 55.0 + frac01(m) * 145.0;
        m = mix(m); this.barrierCenterX = 440.0 + frac01(m) * 144.0;
        m = mix(m); this.barrierMeander = frac01(m) * 96.0;
        m = mix(m); this.barrierPhase = frac01(m) * TAU;
        m = mix(m); this.passZ = 120.0 + frac01(m) * 800.0;
        m = mix(m); this.passWidth = 60.0 + frac01(m) * 180.0;
        m = mix(m); this.steepAmp1 = 16.0 + frac01(m) * 26.0;
        m = mix(m); this.steepAmp2 = 10.0 + frac01(m) * 18.0;
        m = mix(m); this.steepAmp3 = 5.0 + frac01(m) * 11.0;
        m = mix(m); this.bandCount = 3 + (int) (frac01(m) * 3.0);
        m = mix(m); this.bandWidth = 4.0 + frac01(m) * 10.0;
        m = mix(m); this.bandHeight = 12.0 + frac01(m) * 33.0;
        m = mix(m);
        // 火山中心落在主线（对角线 z=x）上：偏置同时作用于 X/Z，保证路径必然与锥面相交。
        double volcanoOffset = (frac01(m) - 0.5) * 200.0;
        this.volcanoCx = 512.0 + volcanoOffset;
        this.volcanoCz = 512.0 + volcanoOffset;
        m = mix(m); this.volcanoRadius = 60.0 + frac01(m) * 140.0;
        m = mix(m); this.volcanoAmp = 70.0 + frac01(m) * 190.0;
    }

    public long seed() {
        return seed;
    }

    public Archetype archetype() {
        return archetype;
    }

    /** 原始地表 Y（未做水面抬升）。带记忆化：同一 (x,z) 只算一次。 */
    public int groundY(int x, int z) {
        int idx = z * (WORLD_MAX_X + 1) + x;
        int c = groundCache[idx];
        if (c != 0) {
            return c;
        }
        int v = computeGroundY(x, z);
        groundCache[idx] = v;
        return v;
    }

    private int computeGroundY(int x, int z) {
        double h = terrainHeight(x, z);

        // 蜿蜒河谷：河心线随 z 正弦摆动，cosine 平滑下挖（中心挖深、边缘零斜率），
        // 河岸与地形自然衔接、无悬崖，跨河路径被迫顺势弯曲。
        double riverCenterX = RIVER_X + MEANDER_AMPLITUDE * Math.sin(z * MEANDER_FREQ + phase4);
        double rdx = Math.abs(x - riverCenterX);
        if (rdx < riverValleyHalfWidth) {
            double t = rdx / riverValleyHalfWidth;
            h -= RIVER_VALLEY_DEPTH * 0.5 * (1.0 + Math.cos(Math.PI * t));
        }

        // 湖盆：同 cosine 平滑下挖。
        double dl = Math.hypot(x - LAKE_X, z - LAKE_Z);
        if (dl < LAKE_VALLEY_RADIUS) {
            double t = dl / LAKE_VALLEY_RADIUS;
            h -= LAKE_VALLEY_DEPTH * 0.5 * (1.0 + Math.cos(Math.PI * t));
        }

        // 起终点锚定为陆地，避免起终点落水导致指标失真。
        // 用「核心区满抬 + 平滑过渡带」而不是硬 max，否则锚点边界会出现一道**人造悬崖**，
        // 把坡度指标污染成与地形无关的常数（起终点恰好是路径的首尾）。
        h = anchorLift(h, Math.hypot(x - START_X, z - START_Z));
        h = anchorLift(h, Math.hypot(x - END_X, z - END_Z));

        return (int) Math.round(h);
    }

    /** 锚点抬升：d ≤ CORE 满抬到海平面+2，CORE..BLEND 之间平滑衰减（最大斜率 1.5/(BLEND-CORE)）。 */
    private static double anchorLift(double h, double d) {
        if (d >= ANCHOR_BLEND_RADIUS) {
            return h;
        }
        double t = d <= ANCHOR_RADIUS
                ? 1.0
                : smoothstep((ANCHOR_BLEND_RADIUS - d) / (ANCHOR_BLEND_RADIUS - ANCHOR_RADIUS));
        return h + Math.max(0.0, SEA_LEVEL + 2.0 - h) * t;
    }

    // ------------------------------------------------------------------
    // 地形原型：每种原型给出「基础地表高度」（河流/湖泊/锚点随后统一施加）
    // ------------------------------------------------------------------

    private double terrainHeight(int x, int z) {
        return switch (archetype) {
            case PLAINS_FLAT -> plainsFlat(x, z);
            case GENTLE_HILLS -> gentleHills(x, z);
            case STEEP_HILLS -> steepHills(x, z);
            case MOUNTAIN_PASS -> mountainPass(x, z);
            case CLIFF_PLATEAU -> cliffPlateau(x, z);
            case CANYON -> canyon(x, z);
            case RIDGE_CROSS -> ridgeCross(x, z);
            case MESA_TERRACES -> mesaTerraces(x, z);
            case VOLCANO -> volcano(x, z);
            case MIXED -> mixed(x, z);
        };
    }

    /** 极缓平原：最大坡度 ~2%~4%。 */
    private double plainsFlat(int x, int z) {
        return baseHeight
                + 2.5 * Math.sin(x * 0.005 + phase1)
                + 2.0 * Math.sin(z * 0.007 + phase2);
    }

    /** 缓丘（原始基线，公式与旧版逐位一致）。 */
    private double gentleHills(int x, int z) {
        double h = baseHeight
                + hillAmp1 * Math.sin(x * 0.012272 + phase1)        // 波长 ~512
                + hillAmp2 * Math.sin(z * 0.016362 + phase2)        // 波长 ~384
                + hillAmp3 * Math.sin((x + z) * 0.021817 + phase3); // 波长 ~288 对角
        if (mountainAmp > 0.5) {
            double dx = x - mountainCx;
            double dz = z - mountainCz;
            h += mountainAmp * Math.exp(-(dx * dx + dz * dz) / (2.0 * mountainSigma * mountainSigma));
        }
        return h;
    }

    /** 陡丘：短波长 + 大振幅，最大坡度 = Σ amp×k ≈ 55%~140%。 */
    private double steepHills(int x, int z) {
        return baseHeight
                + steepAmp1 * Math.sin(x * 0.033 + phase1)          // 波长 ~190
                + steepAmp2 * Math.sin(z * 0.043 + phase2)          // 波长 ~146
                + steepAmp3 * Math.sin((x + z) * 0.055 + phase3);   // 波长 ~114 对角
    }

    /** 南北向屏障中心线（随 z 缓慢蜿蜒，仍横贯全图）。 */
    private double barrierCenterAt(double z) {
        return barrierCenterX + barrierMeander * Math.sin(z * 0.0065 + barrierPhase);
    }

    /** 基础缓丘分量（给含屏障的原型提供起伏，不喧宾夺主）。 */
    private double subduedHills(int x, int z, double scale) {
        return hillAmp1 * scale * Math.sin(x * 0.012272 + phase1)
                + hillAmp2 * scale * Math.sin(z * 0.016362 + phase2)
                + hillAmp3 * scale * Math.sin((x + z) * 0.021817 + phase3);
    }

    /** 高斯山脊屏障：主线必须翻越，最大坡度 ≈ 0.858 × 高度 / 半宽（21%~343%）。 */
    private double mountainPass(int x, int z) {
        double d = (x - barrierCenterAt(z)) / barrierSigma;
        return baseHeight + subduedHills(x, z, 0.5) + barrierAmp * Math.exp(-d * d);
    }

    /**
     * 台地 + 垂直崖面：崖面在 3.5 格水平距离内抬升整个高度 ⇒ 坡度数百 %（不可翻越）；
     * 另开一道「缺口」（沿 z 线性过渡，半宽 passWidth）⇒ 缺口坡度 = 高度 / 半宽（0.2~3.7），
     * 迫使寻路在「绕不开的崖壁」上找到缺口并爬上去——正是分段坡度代价要区分的场景。
     */
    private double cliffPlateau(int x, int z) {
        double d = Math.abs(x - barrierCenterAt(z));
        double halfWidth = barrierSigma * 1.6;
        double edge = 3.5;                                   // 崖面水平宽度（块）
        double t = clamp01((halfWidth - d) / edge);
        double rise = barrierAmp * smoothstep(t);
        double dz = Math.abs(z - passZ);
        if (dz < passWidth) {
            rise *= dz / passWidth;                          // 缺口中线削平，向两侧线性升到满高
        }
        return baseHeight + subduedHills(x, z, 0.4) + rise;
    }

    /**
     * 深谷屏障：先下后上，谷壁最大坡度 ≈ 0.858 × 深度 / 半宽（21%~343%）。
     * 谷宽用 0.5×barrierSigma——否则深度大时整条谷底没入海平面以下，整个世界变成一片水
     * （没窄化前实测 CANYON 的 waterFraction 高达 0.77，与水相关的调参结论会被淹没）。
     */
    private double canyon(int x, int z) {
        double d = (x - barrierCenterAt(z)) / (barrierSigma * 0.5);
        return baseHeight + subduedHills(x, z, 0.5) - barrierAmp * Math.exp(-d * d);
    }

    /** 三角形刃脊：恒定坡度 = 高度 / 半宽（31%~400%），两侧对称。 */
    private double ridgeCross(int x, int z) {
        double d = Math.abs(x - barrierCenterAt(z)) / barrierSigma;
        double ridge = barrierAmp * Math.max(0.0, 1.0 - d);
        return baseHeight + subduedHills(x, z, 0.5) + ridge;
    }

    /** 多层台地：3~5 道南北向崖面台阶，每道坡度 ≈ 1.5 × 台阶高 / 崖面宽（94%~1080%）。 */
    private double mesaTerraces(int x, int z) {
        double h = baseHeight + subduedHills(x, z, 0.4);
        double span = WORLD_MAX_X;
        for (int i = 0; i < bandCount; i++) {
            double c = (i + 1) * span / (bandCount + 1)
                    + barrierMeander * Math.sin(z * 0.006 + barrierPhase + i);
            double u = clamp01((x - c) / bandWidth + 0.5);
            h += bandHeight * smoothstep(u);
        }
        return h;
    }

    /** 火山锥：恒定坡度 = 高度 / 半径（35%~433%），锥心落在主线上，顶部带火口凹陷。 */
    private double volcano(int x, int z) {
        double r = Math.hypot(x - volcanoCx, z - volcanoCz);
        double cone = r < volcanoRadius ? volcanoAmp * (1.0 - r / volcanoRadius) : 0.0;
        double craterR = Math.max(12.0, volcanoRadius * 0.18);
        double crater = volcanoAmp * 0.30 * Math.exp(-(r * r) / (2.0 * craterR * craterR));
        return baseHeight + subduedHills(x, z, 0.5) + cone - crater;
    }

    /** 混合：一道高斯山脊 + 一道深谷，主线先后穿越两种陡度。 */
    private double mixed(int x, int z) {
        double cx1 = barrierCenterX - barrierSigma * 1.2;
        double cx2 = barrierCenterX + barrierSigma * 1.2;
        double d1 = (x - cx1) / (barrierSigma * 0.7);
        double d2 = (x - cx2) / (barrierSigma * 0.4);   // 谷比脊窄：避免谷底大面积没入海平面
        double ridge = barrierAmp * 0.6 * Math.exp(-d1 * d1);
        double cut = barrierAmp * 0.6 * Math.exp(-d2 * d2);
        return baseHeight + subduedHills(x, z, 1.0) + ridge - cut;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    /** 平滑台阶（0→1 之间用 3t²-2t³ 过渡，最大斜率 1.5）。 */
    private static double smoothstep(double t) {
        double c = clamp01(t);
        return c * c * (3.0 - 2.0 * c);
    }

    public boolean isWater(int x, int z) {
        return groundY(x, z) < SEA_LEVEL;
    }

    /**
     * 「群系走向」路由类别（确定性）：WATER=水体，MOUNTAIN=高峰带，SWAMP=近水沼泽带，
     * FOREST/DESERT=两条确定性掩码带，PLAINS=其余。供群系代价表调参验证。
     */
    public PathTerrainField.BiomeCategory biomeCategory(int x, int z) {
        if (isWater(x, z)) return PathTerrainField.BiomeCategory.WATER;
        int g = groundY(x, z);
        if (g >= SEA_LEVEL + MOUNTAIN_ELEVATION) return PathTerrainField.BiomeCategory.MOUNTAIN;
        // 森林斑块：局部化群系障碍（非水、非高峰），验证「绕行 vs 直穿」。
        double df = Math.hypot(x - FOREST_PATCH_X, z - FOREST_PATCH_Z);
        if (df < FOREST_PATCH_RADIUS) return PathTerrainField.BiomeCategory.FOREST;
        if (isNearWater(x, z, SWAMP_BAND)) return PathTerrainField.BiomeCategory.SWAMP;
        double forestMask = Math.sin(x * 0.031416 + z * 0.012566 + phase4);   // 波长 ~200 / ~500
        if (forestMask > 0.35) return PathTerrainField.BiomeCategory.FOREST;
        double desertMask = Math.sin(x * 0.01309 - z * 0.028836 + phase5);    // 波长 ~480 / ~218
        if (desertMask > 0.55) return PathTerrainField.BiomeCategory.DESERT;
        return PathTerrainField.BiomeCategory.PLAINS;
    }

    /** 表面 Y：水体取海平面（水面），陆地取地表。 */
    public int surfaceY(int x, int z) {
        int g = groundY(x, z);
        return g < SEA_LEVEL ? SEA_LEVEL : g;
    }

    /** 底面 Y：水体取水下地面，陆地与地表相同。 */
    public int floorY(int x, int z) {
        return groundY(x, z);
    }

    /** 水深：水体 = 海平面 - 地面，陆地 = 0。 */
    public int waterDepth(int x, int z) {
        int g = groundY(x, z);
        return g < SEA_LEVEL ? (SEA_LEVEL - g) : 0;
    }

    /** 是否在距任意水体 neighborDistance 以内（对蜿蜒河谷/圆形湖盆做解析判定，O(1)）。 */
    public boolean isNearWater(int x, int z, int neighborDistance) {
        double riverCenterX = RIVER_X + MEANDER_AMPLITUDE * Math.sin(z * MEANDER_FREQ + phase4);
        if (Math.abs(x - riverCenterX) < riverValleyHalfWidth + neighborDistance) {
            return true;
        }
        double dl = Math.hypot(x - LAKE_X, z - LAKE_Z);
        return dl < LAKE_VALLEY_RADIUS + neighborDistance;
    }

    public boolean contains(int x, int z) {
        return x >= WORLD_MIN_X && x <= WORLD_MAX_X && z >= WORLD_MIN_Z && z <= WORLD_MAX_Z;
    }

    /**
     * 坡度谱自检（单位：每步高度变化，步长 = 4 格 = A* 步长）。
     * 返回 {最小, 平均, 最大} 的相邻高差绝对值，用于验证原型确实覆盖了各档位。
     */
    public double[] stepDeltaStats(int step) {
        double min = Double.MAX_VALUE;
        double max = 0.0;
        double sum = 0.0;
        long n = 0;
        for (int z = 0; z <= WORLD_MAX_Z; z += step) {
            for (int x = 0; x + step <= WORLD_MAX_X; x += step) {
                double d = Math.abs(groundY(x + step, z) - groundY(x, z));
                min = Math.min(min, d);
                max = Math.max(max, d);
                sum += d;
                n++;
            }
        }
        return new double[]{n == 0 ? 0.0 : min, n == 0 ? 0.0 : sum / n, max};
    }

    /** 地形画像：从种子快速采样得到的 6 个标量特征（创建世界时秒级可算，用于选预设）。 */
    public record Profile(double waterFraction, double elevationMean, double elevationStddev,
                          double mountainFraction, double biomeEntropy, double nearWaterFraction) {
        public String csv(long seed) {
            return seed + "," + f(waterFraction) + "," + f(elevationMean) + "," + f(elevationStddev)
                    + "," + f(mountainFraction) + "," + f(biomeEntropy) + "," + f(nearWaterFraction);
        }
        private static String f(double v) {
            return String.format(java.util.Locale.ROOT, "%.4f", v);
        }
    }

    /** 采样网格（步长 16，65×65=4225 列）计算画像。与生产创建世界时的粗采样同思路。 */
    public Profile profile() {
        int step = 16;
        int n = 0;
        double sumY = 0.0;
        double sumY2 = 0.0;
        int water = 0;
        int mountain = 0;
        int nearWater = 0;
        int[] biomeCounts = new int[PathTerrainField.BiomeCategory.values().length];
        for (int z = 0; z <= WORLD_MAX_Z; z += step) {
            for (int x = 0; x <= WORLD_MAX_X; x += step) {
                int g = groundY(x, z);
                n++;
                sumY += g;
                sumY2 += (double) g * g;
                if (isWater(x, z)) {
                    water++;
                }
                if (g >= SEA_LEVEL + MOUNTAIN_ELEVATION) {
                    mountain++;
                }
                if (isNearWater(x, z, 8)) {
                    nearWater++;
                }
                biomeCounts[biomeCategory(x, z).ordinal()]++;
            }
        }
        double mean = sumY / n;
        double variance = sumY2 / n - mean * mean;
        double stddev = Math.sqrt(Math.max(0.0, variance));
        double entropy = 0.0;
        for (int c : biomeCounts) {
            if (c > 0) {
                double p = c / (double) n;
                entropy -= p * Math.log(p);
            }
        }
        return new Profile(water / (double) n, mean, stddev,
                mountain / (double) n, entropy, nearWater / (double) n);
    }

    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }

    private static double frac01(long x) {
        return (x >>> 11) * (1.0 / (1L << 53));
    }
}
