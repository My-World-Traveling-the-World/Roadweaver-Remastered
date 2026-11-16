/* 文件职责：拥有一次传播的到达值；分页缺省为无穷，完成后交给只读快照。 */
package net.shiroha233.roadweaver.metric;

import java.util.Arrays;

final class FmmValues {
    private final int size;
    private final double[] dense;
    private final FmmPages<double[]> pages;

    FmmValues(int size, boolean paged) {
        this.size = size;
        pages = paged ? new FmmPages<>(size) : null;
        dense = paged ? null : new double[size];
        if (dense != null) Arrays.fill(dense, Double.POSITIVE_INFINITY);
    }

    /** 保留既有测试和稠密参考的数组所有权契约，不再复制整幅时间场。 */
    FmmValues(double[] dense) {
        this.size = dense.length;
        this.dense = dense;
        this.pages = null;
    }

    int size() { return size; }

    double get(int index) {
        if (dense != null) return dense[index];
        double[] page = pages.get(index);
        return page == null ? Double.POSITIVE_INFINITY : page[index & FmmPages.PAGE_MASK];
    }

    void set(int index, double value) {
        if (dense != null) { dense[index] = value; return; }
        double[] page = pages.get(index);
        if (page == null) {
            page = new double[pages.pageLength(index)];
            // Java 的零初始化意味着源点；未写入格必须明确为无穷，不能制造免费连通。
            Arrays.fill(page, Double.POSITIVE_INFINITY);
            pages.put(index, page);
        }
        page[index & FmmPages.PAGE_MASK] = value;
    }
}

/** 冻结标记只属于本次传播，快照仅接管 values，避免长期保留工作状态。 */
final class FmmWorkspace {
    final FmmValues values;
    private final GridFlags denseFrozen;
    private final FmmPages<long[]> frozenPages;

    FmmWorkspace(int size, boolean paged) {
        values = new FmmValues(size, paged);
        denseFrozen = paged ? null : new GridFlags(size);
        frozenPages = paged ? new FmmPages<>(size) : null;
    }

    boolean frozen(int index) {
        if (denseFrozen != null) return denseFrozen.get(index);
        long[] page = frozenPages.get(index);
        int offset = index & FmmPages.PAGE_MASK;
        return page != null && (page[offset >>> 6] & (1L << offset)) != 0;
    }

    void freeze(int index) {
        if (denseFrozen != null) { denseFrozen.set(index); return; }
        long[] page = frozenPages.get(index);
        if (page == null) {
            page = new long[(frozenPages.pageLength(index) + 63) >>> 6];
            frozenPages.put(index, page);
        }
        int offset = index & FmmPages.PAGE_MASK;
        page[offset >>> 6] |= 1L << offset;
    }
}
