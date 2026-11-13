/* 文件职责：结构标签条目，表示一个标签及其包含的所有结构。 */
package net.shiroha233.roadweaver.config.structure;

import java.util.*;
import net.minecraft.resources.ResourceLocation;

/**
 * 结构标签条目，表示一个标签及其包含的所有结构
 */
public final class StructureTagEntry implements Comparable<StructureTagEntry> {
    private final ResourceLocation tagId;
    private final String displayName;
    private final List<StructureEntry> structures;
    private final boolean isVanilla;

    public StructureTagEntry(ResourceLocation tagId, String displayName, List<StructureEntry> structures) {
        this.tagId = tagId;
        this.displayName = displayName;
        this.structures = new ArrayList<>(structures);
        Collections.sort(this.structures);
        this.isVanilla = "minecraft".equals(tagId.getNamespace());
    }

    public ResourceLocation tagId() { return tagId; }
    /**
     * 标签的<b>原始</b>显示名 —— 数据层不负责本地化。
     *
     * <h2>2026-11-13 修正：这里曾经调用客户端中文化字典</h2>
     * <p>原实现是 {@code ChineseRoadWeaverNames.tagName(tagId, displayName)}，两个问题：</p>
     * <ul>
     *   <li><b>层次错乱</b>：{@code config} 是数据层，却反向依赖 {@code client} 的
     *       UI 本地化字典（342 行），使 {@code client <-> config} 成为一对循环依赖；</li>
     *   <li><b>真 bug</b>：{@code StructureDiscoveryService} 把本方法的返回值写进了
     *       结构缓存 —— 于是<b>中文名被固化进磁盘缓存</b>，玩家切到英文后仍显示中文。</li>
     * </ul>
     * <p>现在数据层只给原始名；UI 要本地化请用
     * {@code ChineseRoadWeaverNames.tagDisplayName(tag)}。</p>
     */
    public String displayName() { return displayName; }
    public List<StructureEntry> structures() { return Collections.unmodifiableList(structures); }
    public boolean isVanilla() { return isVanilla; }
    public String namespace() { return tagId.getNamespace(); }

    /** 获取标签形式的字符串（带 # 前缀） */
    public String tagString() { return "#" + tagId.toString(); }

    /** 获取此标签下所有结构的 ID 集合 */
    public Set<String> getAllStructureIds() {
        Set<String> ids = new HashSet<>();
        for (StructureEntry entry : structures) {
            ids.add(entry.id().toString());
        }
        return ids;
    }

    @Override
    public int compareTo(StructureTagEntry other) {
        if (this.isVanilla != other.isVanilla) {
            return this.isVanilla ? -1 : 1;
        }
        return this.tagId.compareTo(other.tagId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StructureTagEntry that)) return false;
        return Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() { return Objects.hash(tagId); }
}
