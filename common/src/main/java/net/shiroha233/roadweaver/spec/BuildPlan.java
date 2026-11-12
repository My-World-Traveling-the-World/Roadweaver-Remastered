/* 文件职责：施工计划（BuildPlan）—— 纯数据：每一列该做什么，不含任何世界访问。 */
package net.shiroha233.roadweaver.spec;

import java.util.ArrayList;
import java.util.List;

/**
 * 施工计划：一条路上<b>每一列</b>该做什么，一份纯数据。
 *
 * <h2>它解决什么问题</h2>
 * <p>旧版把「这一列要不要铺、铺到哪一层、是不是桥面/隧道」的判定<b>散在施工路径里</b>：
 * {@code SegmentPaver.paveSegment} 一边遍历列、一边查世界（{@code isProtected*}）、
 * 一边决定动作。后果是：</p>
 * <ul>
 *   <li><b>测不了</b>：判定依赖世界状态，纯 JUnit 里跑不起来；</li>
 *   <li><b>说不清</b>：「这一列为什么被跳过」只有在跑过之后从日志里猜；</li>
 *   <li><b>改不动</b>：想改判定规则，得在施工循环里做手术。</li>
 * </ul>
 *
 * <p>{@code BuildPlan} 把判定结果<b>物化成数据</b>：谁提供世界信息（保护判定、桥隧归属），
 * 谁就负责把它翻译成输入；{@code BuildPlanCompiler} 只做纯函数式的分类；
 * 施工侧只负责「按 Plan 放方块」。</p>
 *
 * <h2>★ 与 SegmentPaver 的对应关系（将来接线时的对照表）</h2>
 * <table>
 *   <tr><th>本类 Kind</th><th>SegmentPaver 里的对应</th></tr>
 *   <tr><td>{@code SKIP}</td><td>{@code ColumnAction.SKIP}（地表被保护，整列不动）</td></tr>
 *   <tr><td>{@code PAVE_SURFACE_ONLY}</td><td>{@code ColumnAction.PAVE_SURFACE_ONLY}（路基被保护，只铺面层）</td></tr>
 *   <tr><td>{@code PAVE_NORMAL}</td><td>{@code ColumnAction.PAVE_NORMAL}（正常铺装）</td></tr>
 *   <tr><td>{@code BRIDGE_DECK}</td><td>{@code BridgeSegment} 覆盖的站（桥面）</td></tr>
 *   <tr><td>{@code TUNNEL_BORE}</td><td>{@code tunnelBody} 为真的站（隧道洞身）</td></tr>
 * </table>
 *
 * <h2>★ 现状：尚未接线</h2>
 * <p>本类目前<b>只被测试使用</b>，不在生产路径上。接线放在 S7 第 2 级，因为那时
 * 判定（桥隧/保护）才是稳定的。之所以现在先把数据模型定下来，是因为
 * S7 第 2 级要写的「旧判定翻译成模式约束」正好需要这样一个目标结构 ——
 * 先定形状，后填内容，比边写边改形状便宜。</p>
 *
 * <p>接线时需要补的输入见 {@code BuildPlanCompiler.Inputs} 的注释。</p>
 */
public record BuildPlan(List<Column> columns) {

    /** 一列该做什么。 */
    public enum Kind {
        /** 整列不动（地表被保护，或属于必须保留的结构）。 */
        SKIP,
        /** 只铺面层（路基被保护，不能动下面的方块）。 */
        PAVE_SURFACE_ONLY,
        /** 正常铺装（面层 + 路基）。 */
        PAVE_NORMAL,
        /** 桥面（桥段覆盖的站）。 */
        BRIDGE_DECK,
        /** 隧道洞身（隧道段覆盖的站）。 */
        TUNNEL_BORE
    }

    /**
     * 一列。
     *
     * @param station 属于第几站（用于回查 profile / span 归属）
     * @param index   在该站内的列序号（0 = 断面一端）
     * @param x       世界 x
     * @param y       该列的目标高度
     * @param z       世界 z
     * @param kind    该列该做什么
     */
    public record Column(int station, int index, int x, int y, int z, Kind kind) {
    }

    public BuildPlan {
        columns = List.copyOf(columns);
    }

    /** 总列数。 */
    public int size() {
        return columns.size();
    }

    /** 某种动作的列数。 */
    public int count(Kind kind) {
        return (int) columns.stream().filter(c -> c.kind() == kind).count();
    }

    /** 需要放方块的列数（SKIP 之外）。 */
    public int placeable() {
        return size() - count(Kind.SKIP);
    }

    /** 按站分组（station → 该站的列）。 */
    public List<List<Column>> byStation() {
        if (columns.isEmpty()) return List.of();
        int maxStation = columns.stream().mapToInt(Column::station).max().orElse(0);
        List<List<Column>> out = new ArrayList<>(maxStation + 1);
        for (int i = 0; i <= maxStation; i++) out.add(new ArrayList<>());
        for (Column c : columns) out.get(c.station()).add(c);
        return out;
    }

    /** 人类可读的一行（日志/断言用）。 */
    public String summary() {
        return "cols=" + size() + " placeable=" + placeable()
                + " skip=" + count(Kind.SKIP)
                + " surfaceOnly=" + count(Kind.PAVE_SURFACE_ONLY)
                + " normal=" + count(Kind.PAVE_NORMAL)
                + " bridge=" + count(Kind.BRIDGE_DECK)
                + " tunnel=" + count(Kind.TUNNEL_BORE);
    }
}