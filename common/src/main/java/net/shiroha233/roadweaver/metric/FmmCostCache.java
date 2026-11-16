/* 文件职责：复用与源点无关的 FMM 代价；小网格整场缓存，大网格惰性准备。 */
package net.shiroha233.roadweaver.metric;

/** 度量在求解器寿命内不可变；分页仅改变读写时机，不改变 Godunov 或边的方向。 */
final class FmmCostCache {
    private final FinslerMetric metric;
    private final GridLayout grid;
    private final double[] cachedCostX;
    private final double[] cachedCostZ;
    private final GridFlags cachedPassable;
    private final byte[] cachedEdgeBlocks;
    private final FmmPages<Page> pages;

    FmmCostCache(FinslerMetric metric, GridLayout grid, boolean paged) {
        this.metric = metric;
        this.grid = grid;
        int size = grid.arrayLength(), w = grid.columns(), d = grid.rows();
        if (paged) {
            cachedCostX = cachedCostZ = null;
            cachedPassable = null;
            cachedEdgeBlocks = null;
            pages = new FmmPages<>(size);
            return;
        }
        pages = null;
        double[] costX = new double[size];
        double[] costZ = new double[size];
        // 通行与冻结都是一位状态，不必为每格各占一个字节。
        GridFlags passable = new GridFlags(size);
        for (int iz = 0; iz < d; iz++) {
            for (int ix = 0; ix < w; ix++) {
                int index = ix + iz * w;
                int bx = grid.minX() + ix * grid.step();
                int bz = grid.minZ() + iz * grid.step();
                if (metric.isPassable(bx, bz)) passable.set(index);
                // 硬禁格从不入队，无需再读坡度、障碍势或代价优惠。
                if (passable.get(index)) metric.writeAxialCosts(bx, bz, grid.step(), index, costX, costZ);
            }
        }
        cachedCostX = costX;
        cachedCostZ = costZ;
        cachedPassable = passable;
        cachedEdgeBlocks = metric.hasEdgeBlock()
                ? precomputeBlockedEdges(grid.minX(), grid.minZ(), w, d, grid.step(), passable) : null;
    }

    private byte[] precomputeBlockedEdges(int minX, int minZ, int w, int d, int step, GridFlags passable) {
        byte[] edges = new byte[w * d];
        for (int iz = 0; iz < d; iz++) {
            for (int ix = 0; ix < w; ix++) {
                int i = ix + iz * w;
                if (!passable.get(i)) continue;
                int x = minX + ix * step, z = minZ + iz * step;
                // 只读每条无向边一次；推进和逆向取邻居使用同一份结果。
                if (ix + 1 < w && passable.get(i + 1) && metric.blocksEdge(x, z, x + step, z)) {
                    edges[i] |= 1;
                    edges[i + 1] |= 2;
                }
                if (iz + 1 < d && passable.get(i + w) && metric.blocksEdge(x, z, x, z + step)) {
                    edges[i] |= 4;
                    edges[i + w] |= 8;
                }
            }
        }
        return edges;
    }

    boolean passable(int index) {
        if (pages == null) return cachedPassable.get(index);
        Page page = page(index);
        int offset = index & FmmPages.PAGE_MASK;
        if ((page.state[offset] & 1) == 0) {
            int x = grid.minX() + (index % grid.columns()) * grid.step();
            int z = grid.minZ() + (index / grid.columns()) * grid.step();
            // 查询成功后才发布“已知”，异常不能被缓存成正常禁行结果。
            page.state[offset] |= (byte) (metric.isPassable(x, z) ? 3 : 1);
        }
        return (page.state[offset] & 2) != 0;
    }

    void prepare(int index) {
        if (pages == null) return;
        Page page = page(index);
        int offset = index & FmmPages.PAGE_MASK;
        if ((page.state[offset] & 64) != 0) return;
        int x = grid.minX() + (index % grid.columns()) * grid.step();
        int z = grid.minZ() + (index / grid.columns()) * grid.step();
        metric.writeAxialCosts(x, z, grid.step(), offset, page.costX, page.costZ);
        page.state[offset] |= 64;
    }

    double costX(int index) {
        return pages == null ? cachedCostX[index] : pages.get(index).costX[index & FmmPages.PAGE_MASK];
    }

    double costZ(int index) {
        return pages == null ? cachedCostZ[index] : pages.get(index).costZ[index & FmmPages.PAGE_MASK];
    }

    int blockedEdges(int index) {
        if (!metric.hasEdgeBlock()) return 0;
        if (pages == null) return cachedEdgeBlocks[index];
        int w = grid.columns(), ix = index % w, iz = index / w;
        int mask = 0;
        if (ix + 1 < w && blockedForward(index, 1)) mask |= 1;
        if (ix > 0 && blockedForward(index - 1, 1)) mask |= 2;
        if (iz + 1 < grid.rows() && blockedForward(index, w)) mask |= 4;
        if (iz > 0 && blockedForward(index - w, w)) mask |= 8;
        return mask;
    }

    private boolean blockedForward(int owner, int delta) {
        Page page = page(owner);
        int offset = owner & FmmPages.PAGE_MASK;
        // 每条无向边仍由西/北端点按东/南顺序查询，与原整场预计算完全相同。
        // 用端点位置区分单列网格的“向南”，不能把 delta=1 一律当向东。
        boolean east = delta == 1 && grid.columns() > 1;
        int known = east ? 4 : 16, blocked = east ? 8 : 32;
        if ((page.state[offset] & known) == 0) {
            int x = grid.minX() + (owner % grid.columns()) * grid.step();
            int z = grid.minZ() + (owner / grid.columns()) * grid.step();
            boolean result = passable(owner) && passable(owner + delta)
                    && metric.blocksEdge(x, z, east ? x + grid.step() : x,
                            east ? z : z + grid.step());
            page.state[offset] |= (byte) (known | (result ? blocked : 0));
        }
        return (page.state[offset] & blocked) != 0;
    }

    private Page page(int index) {
        Page page = pages.get(index);
        if (page == null) {
            page = new Page(pages.pageLength(index));
            pages.put(index, page);
        }
        return page;
    }

    private static final class Page {
        final double[] costX, costZ;
        // 位域依次为通行已知、可通行、东边已知/禁行、南边已知/禁行、代价已知。
        final byte[] state;

        Page(int length) {
            costX = new double[length];
            costZ = new double[length];
            state = new byte[length];
        }
    }
}
