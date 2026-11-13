/* 文件职责：剖面求解器的模式开关 —— 默认 LEGACY，DP 仅显式开启。 */
package net.shiroha233.roadweaver.pathlogic.profile;

/**
 * 剖面求解器模式。
 *
 * <h2>为什么要有开关，而不是直接换掉</h2>
 * <p>新 DP 内核已经被证明最优（300 组暴力枚举对拍）、在离线研究里土方量远优于旧 pass 流水线，
 * 但它<b>还没在真实存档上跑过</b>。两者的差别不是"谁更聪明"，而是
 * <b>旧流水线里塞满了针对真实地形踩出来的补丁</b>（桥头台阶、河岸跳变、保护区边界……），
 * 新内核目前只补了三条硬契约。</p>
 *
 * <p>所以顺序必须是：默认走旧链路 → 用开关在真实存档上收集数据 → 差异可解释 → 再改默认。
 * 这个开关就是那条"能收数据又不冒险"的路径。</p>
 *
 * <h2>★ 默认值必须让行为逐字节不变</h2>
 * <p>{@link #LEGACY} 分支<b>直接返回原剖面</b>，不做任何计算、不建任何对象。
 * 这样「没设属性」与「设了 legacy」完全等价，CI 才能用默认路径证明我没动过东西。</p>
 *
 * <h2>怎么用</h2>
 * <pre>
 *   -Droadweaver.profileSolver=dp       使用全局 DP 求解剖面
 *   （不设 / legacy / 任何非法值）      走旧 15 道 pass 流水线
 * </pre>
 *
 * <p>非法值一律回落 {@link #LEGACY} 并只记一次警告 —— 拼错属性名不该让世界生成行为变掉。</p>
 */
public enum ProfileSolverMode {

    /** 旧 15 道 pass 流水线（保留作为显式回退路径）。 */
    LEGACY,

    /** 全局 DP 求解（<b>非默认，需 -Droadweaver.profileSolver=dp 显式开启</b>）。 */
    DP;

    /** 系统属性名。 */
    public static final String PROPERTY = "roadweaver.profileSolver";

    /**
     * 从系统属性读取模式。
     *
     * <h2>★ 2026-11-13 二次变更：默认从 DP 切回 LEGACY</h2>
     *
     * <p>同日早些时候曾把默认切成 {@code DP}，依据是「DP 土方远优」（57 次观测，
     * 土方 325,346 → 8,996）加上三项前置条件（二阶坡度约束 / 不可行回落 / AB 数据）。
     * 但随后三份独立只读核查（纵断面 ×2、桥隧路口 ×1）发现：</p>
     *
     * <p>{@code TargetYComputation:463} 是在<b>所有旧 pass 跑完之后整条替换剖面</b>，
     * 而 DP 的输入只有 {@code terrainY / crossing / tunnelMask / 端点 pin / plainCut /
     * plainFill / maxStep} —— <b>它拿不到水位</b>（{@code ProfileDpSource} 全文
     * {@code water} 出现 0 次）。于是：</p>
     *
     * <ul>
     *   <li><b>桥面从平甲板变成斜弦</b>：旧版是
     *       {@code BridgeDeckProfile.minimumDeckY = water + deckClearance}（默认 +2）；
     *       DP 是两岸 {@code terrainY} 线性内插。海平面河（河床 60 / 水面 63）
     *       甲板 65 → 63，下沉 2 格贴水；山间河（河床 100 / 水面 103）
     *       甲板 105 → 100，<b>比水面低 3 格、泡在水下</b>。</li>
     *   <li><b>prefer-cut 契约被翻转</b>：旧流水线「只降不升、绝不造天桥」；
     *       DP 的代价 {@code cut=1.0 / fill=0.7}，三站地形 [99,100,101] 会选
     *       [100,100,101] 垫高而不是 [99,100,100] 削顶 ⇒ 路面浮在地面之上。</li>
     *   <li><b>仅 LEGACY 生效的旧补丁约 10 项</b>：浅滩抬升与两岸切平、相邻桥甲板
     *       连续性、峡谷稳定岸 {@code applyStableBankRanges}、河岸跳变
     *       {@code closeTunnelWaterBoundary}、针尖 {@code removeSingleStationSpikes}、
     *       1-2-1 平滑 {@code smoothProfileWithinBudget}、{@code GradeCap} 档位、
     *       山区 cut/fill 分档、共享引道 {@code applySharedApproachProfile}。</li>
     * </ul>
     *
     * <p><b>所以默认切回 {@code LEGACY}</b>：先保证旧版行为完整，再把上面这些能力
     * 一项一项补进 DP 内核，每补一项单独验证。DP 仍然可用：</p>
     * <pre>
     *   -Droadweaver.profileSolver=dp      显式启用 DP
     *   （不设 / legacy / 任何非法值）      走旧 15 道 pass 流水线
     * </pre>
     *
     * <p><b>不可行回落的语义保持不变</b>：DP 判不可行会回落到旧流水线并记 WARN，
     * 绝不让一条路算不出剖面。</p>
     *
     * @return 合法值返回对应模式；未设或非法返回 {@link #LEGACY}
     */
    public static ProfileSolverMode fromProperty() {
        return parse(System.getProperty(PROPERTY));
    }

    /** 解析字符串（大小写不敏感）；未设或非法返回 {@link #LEGACY}（默认）。 */
    public static ProfileSolverMode parse(String raw) {
        if (raw == null || raw.isBlank()) return LEGACY;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("dp".equals(v)) return DP;
        if ("legacy".equals(v) || "classic".equals(v)) return LEGACY;
        return LEGACY;
    }
}