/* 文件职责：区分本批需要交给施工的区块，与磁盘提交确实改变的区块。 */
package net.shiroha233.roadweaver.persistence.chunk;

import java.util.Set;

public record RoadChunkChanges(Set<Long> delivered, Set<Long> changed) {
    public static final RoadChunkChanges EMPTY = new RoadChunkChanges(Set.of(), Set.of());

    /** 存储索引与世界的 X/Z 位序相反；只在提交返回时换算，不动存档。 */
    static RoadChunkChanges committed(Set<Long> delivered, Set<Long> changed) {
        return new RoadChunkChanges(RoadFootprint.toWorldKeys(delivered), RoadFootprint.toWorldKeys(changed));
    }
}
