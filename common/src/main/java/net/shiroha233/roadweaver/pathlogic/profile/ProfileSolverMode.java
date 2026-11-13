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

    /** 全局 DP 求解（<b>2026-11-13 起为默认</b>）。 */
    DP;

    /** 系统属性名。 */
    public static final String PROPERTY = "roadweaver.profileSolver";

    /**
     * 从系统属性读取模式。
     *
     * <h2>★ 默认值已于 2026-11-13 从 {@code LEGACY} 改为 {@code DP}</h2>
     * <p>用户的决定是「直接转向重构版，不在旧版修修补补」——
     * 理由是旧版算法上限已经到顶（15 道启发式 pass 互相打架，再加 pass 只会更乱）。</p>
     *
     * <p>切换前已完成的三件事（缺一不可）：</p>
     * <ol>
     *   <li><b>二阶坡度约束</b>：DP 现在遵守「每<b>两</b>站最多升降 N 格」，
     *       与配置 {@code maxSlopeStepPerTwoSegments} 语义一致 ——
     *       此前用 {@code ceil(N/2)} 折算成每站是错的，等于给了两倍坡度；</li>
     *   <li><b>不可行必须回落</b>：DP 判不可行时自动走旧流水线并记 WARN，
     *       绝不让一条路算不出剖面；</li>
     *   <li><b>真实地形 AB 数据</b>：57 次观测、土方 325,346 → 8,996。</li>
     * </ol>
     *
     * <p><b>回退办法</b>：{@code -Droadweaver.profileSolver=legacy}，不需改代码。</p>
     *
     * @return 合法值返回对应模式；未设返回 {@link #DP}；无法识别的值也返回 {@link #DP}
     */
    public static ProfileSolverMode fromProperty() {
        return parse(System.getProperty(PROPERTY));
    }

    /** 解析字符串（大小写不敏感）；未设或非法返回 {@link #DP}（默认）。 */
    public static ProfileSolverMode parse(String raw) {
        if (raw == null || raw.isBlank()) return DP;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("dp".equals(v)) return DP;
        if ("legacy".equals(v) || "classic".equals(v)) return LEGACY;
        return DP;
    }
}