/* 文件职责：为 FMM 的稀疏页提供两级目录，避免仅建目录就按全场格数分配引用。 */
package net.shiroha233.roadweaver.metric;

/** 页按线性下标分组；未触及的页没有载荷，读取不会隐式创建页。 */
final class FmmPages<P> {
    static final int PAGE_BITS = 10;
    static final int PAGE_SIZE = 1 << PAGE_BITS;
    static final int PAGE_MASK = PAGE_SIZE - 1;
    private static final int GROUP_BITS = 8;
    private static final int GROUP_MASK = (1 << GROUP_BITS) - 1;
    private final Object[][] groups;
    private final int size;

    FmmPages(int size) {
        if (size <= 0) throw new IllegalArgumentException("positive grid size required");
        this.size = size;
        // 顶层每项覆盖 262144 格；大范围近目标不能先付出全部页引用的成本。
        groups = new Object[(int) ((size + (1L << (PAGE_BITS + GROUP_BITS)) - 1)
                >>> (PAGE_BITS + GROUP_BITS))][];
    }

    @SuppressWarnings("unchecked")
    P get(int index) {
        Object[] group = groups[index >>> (PAGE_BITS + GROUP_BITS)];
        return group == null ? null : (P) group[(index >>> PAGE_BITS) & GROUP_MASK];
    }

    void put(int index, P page) {
        int groupIndex = index >>> (PAGE_BITS + GROUP_BITS);
        Object[] group = groups[groupIndex];
        if (group == null) groups[groupIndex] = group = new Object[1 << GROUP_BITS];
        group[(index >>> PAGE_BITS) & GROUP_MASK] = page;
    }

    int pageLength(int index) {
        // 尾页只分配实际格数，避免小夹具和极限下标出现虚构的网格空间。
        return Math.min(PAGE_SIZE, size - (index & ~PAGE_MASK));
    }
}
