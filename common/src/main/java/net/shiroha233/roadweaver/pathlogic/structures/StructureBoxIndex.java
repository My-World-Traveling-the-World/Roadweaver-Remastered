/* 文件职责：结构盒的网格空间索引 —— 把「全表扫描」换成「只扫重叠网格」。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 结构盒的网格空间索引。
 *
 * <h2>为什么需要它</h2>
 * <p>避让判定是<b>逐列查询</b>：路每前进一格都要问「这一列会不会撞到某个结构」。
 * 如果每次都遍历整批结构盒，代价是 {@code O(列数 × 盒数)} —— 一条 2000 站的路
 * 配 4000 个盒就是 800 万次区间比较，而这些盒里 99% 根本不在附近。</p>
 *
 * <p>本类把盒按固定边长的网格分桶，查询时只取与查询矩形重叠的那几个桶。
 * 典型情形（盒边长 ≤ 一个桶）下，每次查询只看 1~4 个桶，命中常数个候选。</p>
 *
 * <h2>为什么桶边长默认 16</h2>
 * <p>16 正好是一个区块。结构盒的自然粒度就是区块级的（一个结构往往就在 1~2 个
 * 区块内），所以按区块分桶时「一个盒通常只落 1~2 个桶」，重桶率最低。
 * 边长越小越精确但跨桶越多，越大则单桶越胖 —— 16 是这两者的平衡点。</p>
 *
 * <h2>★ 语义保证（这是它与线性扫描等价的前提）</h2>
 * <ul>
 *   <li>{@link #query} 返回的是<b>候选超集</b>：凡是与查询矩形相交的盒一定在结果里，
 *       但结果里可能有实际不相交的（索引只按包围盒分桶，不做精判）。
 *       调用方仍须用 {@code OwnedStructureBox.intersects(...)} 精判 —— 这一点是
 *       <b>刻意的</b>：索引只负责「别漏」，精判负责「别错」。</li>
 *   <li>{@code box == null} 或 {@code owner == null} 的盒子会被<b>跳过</b>，
 *       与线性扫描里那些显式的 null 检查保持一致。</li>
 *   <li>同一个盒跨多个桶时只出现一次（结果去重）。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>构建完成后是不可变视图，可被多个 worker 线程并发查询。
 * 构建期的 {@code ConcurrentHashMap} 只是为了让构建语义简单，
 * 不承诺构建期并发写。</p>
 */
public final class StructureBoxIndex {

    /** 默认桶边长（= 一个区块）。 */
    public static final int DEFAULT_CELL_SIZE = 16;

    private final int cellSize;
    private final Map<Long, List<OwnedStructureBox>> cells;
    private final Map<OwnedStructureBox, BoundingBox> boxes;
    private final int size;

    private StructureBoxIndex(int cellSize, Map<Long, List<OwnedStructureBox>> cells,
                              Map<OwnedStructureBox, BoundingBox> boxes, int size) {
        this.cellSize = cellSize;
        this.cells = cells;
        this.boxes = boxes;
        this.size = size;
    }

    /**
     * 构建索引。
     *
     * @param source   结构盒集合，允许 null / 空
     * @param cellSize 桶边长，必须 &gt; 0；传入非正数时退回 {@link #DEFAULT_CELL_SIZE}
     */
    public static StructureBoxIndex of(Collection<OwnedStructureBox> source, int cellSize) {
        int cs = cellSize > 0 ? cellSize : DEFAULT_CELL_SIZE;
        Map<Long, List<OwnedStructureBox>> cells = new ConcurrentHashMap<>();
        Map<OwnedStructureBox, BoundingBox> boxes = new java.util.IdentityHashMap<>();
        int count = 0;
        if (source != null) {
            for (OwnedStructureBox owned : source) {
                if (owned == null || owned.owner() == null) continue;
                BoundingBox box = owned.box();
                if (box == null) continue;
                boxes.put(owned, box);
                count++;
                int cx0 = Math.floorDiv(box.minX(), cs);
                int cx1 = Math.floorDiv(box.maxX(), cs);
                int cz0 = Math.floorDiv(box.minZ(), cs);
                int cz1 = Math.floorDiv(box.maxZ(), cs);
                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cz = cz0; cz <= cz1; cz++) {
                        cells.computeIfAbsent(cellKey(cx, cz), k -> new ArrayList<>()).add(owned);
                    }
                }
            }
        }
        return new StructureBoxIndex(cs, cells, boxes, count);
    }

    /** 用默认桶边长构建。 */
    public static StructureBoxIndex of(Collection<OwnedStructureBox> source) {
        return of(source, DEFAULT_CELL_SIZE);
    }

    /** 空索引。 */
    public static StructureBoxIndex empty() {
        return of(null, DEFAULT_CELL_SIZE);
    }

    /**
     * 查询与给定水平矩形可能相交的结构盒。
     *
     * <p>返回的是<b>候选超集</b>，调用方必须自己再做精确相交判定。</p>
     */
    public List<OwnedStructureBox> query(int minX, int minZ, int maxX, int maxZ) {
        if (size == 0) return List.of();
        int loX = Math.min(minX, maxX);
        int hiX = Math.max(minX, maxX);
        int loZ = Math.min(minZ, maxZ);
        int hiZ = Math.max(minZ, maxZ);
        int cx0 = Math.floorDiv(loX, cellSize);
        int cx1 = Math.floorDiv(hiX, cellSize);
        int cz0 = Math.floorDiv(loZ, cellSize);
        int cz1 = Math.floorDiv(hiZ, cellSize);
        LinkedHashSet<OwnedStructureBox> found = new LinkedHashSet<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<OwnedStructureBox> bucket = cells.get(cellKey(cx, cz));
                if (bucket == null) continue;
                // 桶内的盒可能整个落在查询矩形之外（桶比矩形大），这里先按包围盒粗筛一遍，
                // 精判仍留给调用方 —— 索引只保证「不漏」。
                for (OwnedStructureBox owned : bucket) {
                    BoundingBox box = boxes.get(owned);
                    if (box == null) continue;
                    if (box.maxX() < loX || box.minX() > hiX
                            || box.maxZ() < loZ || box.minZ() > hiZ) continue;
                    found.add(owned);
                }
            }
        }
        return found.isEmpty() ? List.of() : List.copyOf(found);
    }

    /** 索引里的结构盒数量（构建时跳过的 null 盒不计）。 */
    public int size() {
        return size;
    }

    /** 实际用到的桶数量（用于诊断与测试）。 */
    public int cellCount() {
        return cells.size();
    }

    /** 桶边长。 */
    public int cellSize() {
        return cellSize;
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
