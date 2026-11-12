/* 文件职责：一条路的唯一权威表示（只读）—— 中心线 + 剖面 + 区间，且区间带站索引。 */
package net.shiroha233.roadweaver.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

/**
 * 一条路的唯一权威表示。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link RoadData} 里其实已经装着三套事实 ——
 * {@code roadSegmentList}（中心线站）、{@code spans}（区间）、{@code targetY}（剖面）。
 * 问题不在于缺容器，而在于<b>没有任何东西把它们当一个整体对待</b>：</p>
 *
 * <ul>
 *   <li>剖面被算了 <b>6 次</b>（{@code computeTargetY} 6 处调用，每次内部 15 道 pass）；</li>
 *   <li>区间被提取/重分类 <b>14 次</b>；</li>
 *   <li>三套事实各自可以独立改写，谁的版本是真的没人定义 —— 于是收敛方式是
 *       「跑完再校验再修」，而不是不变量。</li>
 * </ul>
 *
 * <h2>★ 它顺手修掉的一整类 bug</h2>
 * <p>{@link RoadSpan} 只存 {@code BlockPos}、<b>没有站索引</b>。任何需要「这段桥覆盖第几站到第几站」
 * 的代码，都得拿 {@code BlockPos.equals} 去站序列里反查 —— 而路网合并会重建站序列，
 * <b>Y 漂一格就整座桥被静默丢掉</b>（0.9.036 的 {@code bridgeShareCandidates=0} 就是这个）。
 * 当时的修法是加一个「同 XZ 唯一 + |ΔY|≤4」的兜底，属于绕过而不是解决。</p>
 *
 * <p>本类在<b>构造时一次性</b>把每个区间解析成站索引，此后所有人读索引，
 * 不再有「反查失败」这条路径。</p>
 *
 * <h2>只读约定</h2>
 * <p>{@link RoadData} 的紧凑构造器已经 {@code List.copyOf} 过全部列表，所以这里拿到的
 * 一定是<b>不可变</b>集合；本类不再复制一次（多一份副本就多一份「谁是真的」）。</p>
 *
 * <p>因此约定：<b>谁要改路，就连 {@code RoadData} 一起换掉并重新 {@code from()}</b>，
 * 不要指望原地改列表。{@code RoadData} 自己的 javadoc 也写着 targetY 是
 * 「calculated once … every later stage must consume the same immutable profile」——
 * 那句话就是本类存在的理由，只不过现在才有东西真的去检查它。</p>
 *
 * @see #from(RoadData)
 */
public record RoadSpec(
        /** 中心线站序列（与 {@code RoadData.roadSegmentList()} 同一对象，不复制）。 */
        List<RoadSegmentPlacement> stations,
        /** 每站地面高度（与 {@code RoadData.targetY()} 同尺寸）。 */
        int[] profile,
        /** 区间（带站索引），按起始站升序、互不重叠。 */
        List<Section> sections,
        long ownerA2dKey,
        long ownerB2dKey) {

    /** 一个区间：类型 + 站索引闭区间。 */
    public record Section(SpanType type, int fromStation, int toStation) {

        /** 覆盖的站数。 */
        public int length() {
            return toStation - fromStation + 1;
        }

        /** 是否包含某站。 */
        public boolean covers(int station) {
            return station >= fromStation && station <= toStation;
        }
    }

    /** 构造失败时抛这个 —— 带上足够定位的上下文，不用去猜。 */
    public static final class SpecViolationException extends IllegalStateException {
        public SpecViolationException(String message) {
            super(message);
        }
    }

    public RoadSpec {
        if (stations == null) throw new SpecViolationException("stations 不能为 null");
        if (profile == null) throw new SpecViolationException("profile 不能为 null");
        if (profile.length != stations.size()) {
            throw new SpecViolationException("剖面长度与站数不一致：profile=" + profile.length
                    + " stations=" + stations.size() + "（这两者必须是同一批站，否则后面每一次"
                    + "「按站取高度」都在越界或错位）");
        }
        sections = List.copyOf(sections);
        int prevEnd = -1;
        for (Section s : sections) {
            if (s.fromStation() < 0 || s.toStation() >= stations.size()) {
                throw new SpecViolationException("区间越界：" + s + "，站数=" + stations.size());
            }
            if (s.fromStation() > s.toStation()) {
                throw new SpecViolationException("区间起止颠倒：" + s);
            }
            if (s.fromStation() <= prevEnd) {
                throw new SpecViolationException("区间重叠或未排序：" + s + "（上一段止于 " + prevEnd + "）");
            }
            prevEnd = s.toStation();
        }
    }

    /**
     * 从 {@link RoadData} 构造。
     *
     * <h2>区间如何解析成站索引</h2>
     * <p>优先用 {@code BlockPos.equals} 精确匹配中间站 —— 命中率最高、语义最硬。
     * 精确匹配不到时退到「同 XZ 且全序列唯一」的匹配（路网合并会重建站序列导致 Y 漂一格），
     * 但<b>同 XZ 出现多个站就视为歧义直接失败</b> —— 宁可报错，也不要静默错配出一条自交的路。</p>
     *
     * <p>解析失败不再「跳过这段」，而是抛 {@link SpecViolationException}：
     * 跳过正是当年那座桥被静默丢掉的机制。</p>
     */
    public static RoadSpec from(RoadData data) {
        if (data == null) throw new SpecViolationException("data 不能为 null");
        return of(data.roadSegmentList(), data.targetY(), data.spans(),
                data.ownerA2dKey(), data.ownerB2dKey());
    }

    /**
     * 直接由三套事实构造 —— 不需要先有 {@link RoadData}。
     *
     * <h2>为什么要单独开这个入口</h2>
     * <p>规划中途（区间刚提取、剖面刚算完）手里只有这三样东西，还没有 {@code RoadData}。
     * 而且 {@code RoadData} 的静态 {@code CODEC} 会触发 {@code BlockState} 类初始化，
     * 在纯 JUnit 环境里不可用 —— 解析逻辑必须能脱离它单独被测。</p>
     *
     * <p>{@link #from(RoadData)} 只是这个方法的适配器：校验与解析只写在这里一处。</p>
     */
    public static RoadSpec of(List<RoadSegmentPlacement> stations, List<Integer> targetY,
                              List<RoadSpan> spans, long ownerA2dKey, long ownerB2dKey) {
        if (stations == null) throw new SpecViolationException("roadSegmentList 为 null");
        if (targetY == null) throw new SpecViolationException("targetY 为 null");

        int[] profile = new int[targetY.size()];
        for (int i = 0; i < profile.length; i++) {
            Integer v = targetY.get(i);
            if (v == null) {
                throw new SpecViolationException("targetY[" + i + "] 为 null —— 剖面里不允许出现空洞");
            }
            profile[i] = v;
        }

        List<Section> sections = new ArrayList<>();
        if (spans != null) {
            for (RoadSpan span : spans) {
                if (span == null) continue;
                int from = resolveStation(stations, span.start(), "start", span);
                int to = resolveStation(stations, span.end(), "end", span);
                sections.add(new Section(span.type(), Math.min(from, to), Math.max(from, to)));
            }
        }
        sections.sort((a, b) -> Integer.compare(a.fromStation(), b.fromStation()));

        return new RoadSpec(stations, profile, sections, ownerA2dKey, ownerB2dKey);
    }

    private static int resolveStation(List<RoadSegmentPlacement> stations, BlockPos pos,
                                      String which, RoadSpan span) {
        if (pos == null) {
            throw new SpecViolationException("区间 " + span + " 的 " + which + " 为 null");
        }
        for (int i = 0; i < stations.size(); i++) {
            BlockPos middle = stations.get(i) == null ? null : stations.get(i).middlePos();
            if (pos.equals(middle)) return i;
        }
        int hit = -1;
        for (int i = 0; i < stations.size(); i++) {
            BlockPos middle = stations.get(i) == null ? null : stations.get(i).middlePos();
            if (middle == null) continue;
            if (middle.getX() == pos.getX() && middle.getZ() == pos.getZ()) {
                if (hit >= 0) {
                    throw new SpecViolationException("区间 " + span + " 的 " + which
                            + " 在站序列里有多个同 XZ 候选（" + hit + " 与 " + i
                            + "）—— 歧义必须失败，静默选一个会配出错误的路段归属");
                }
                hit = i;
            }
        }
        if (hit >= 0) return hit;
        throw new SpecViolationException("区间 " + span + " 的 " + which + " 在站序列里找不到对应站"
                + "（精确匹配与同 XZ 唯一匹配都失败）—— 这正是「桥被静默丢掉」的原始机制");
    }

    /** 站数。 */
    public int stationCount() {
        return stations.size();
    }

    /** 第 i 站的剖面高度。 */
    public int heightAt(int station) {
        return profile[station];
    }

    /** 第 i 站所属的区间；不在任何区间内返回 {@code null}。 */
    public Section sectionAt(int station) {
        for (Section s : sections) {
            if (s.covers(station)) return s;
        }
        return null;
    }

    /** 某类型的全部区间。 */
    public List<Section> sectionsOf(SpanType type) {
        List<Section> out = new ArrayList<>();
        for (Section s : sections) {
            if (s.type() == type) out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    /** 未被任何区间覆盖的站数（诊断用：应当等于「普通路面」站数）。 */
    public int uncoveredStations() {
        int n = 0;
        for (int i = 0; i < stations.size(); i++) {
            if (sectionAt(i) == null) n++;
        }
        return n;
    }
}