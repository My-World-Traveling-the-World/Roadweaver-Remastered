/* 文件职责：把同一批规划的「过河点」按 XZ 邻近聚簇，为每簇选一个共享过河点（纯函数）。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeShareService;

/**
 * 规划期过河点协商（0.9.057，「并行 → 协商 → 并行」三段式的中间那一段）。
 *
 * <h2>为什么需要它</h2>
 * <p>真机 {@code (236,-170)}：出生点 {@code (32,-64)} 分出两条连接各去一个村庄，规划阶段
 * 两条路<b>各自独立跑 A*</b>，各看见水就各架桥 ⇒ 甲板 {@code Y=48} 与 {@code Y=65} 差 17 格，
 * 生成后处理（{@code BridgeShareService}）已经合不回来。0.9.056 为了让「后算的路被先算完的
 * 路吸过去」，把粗走廊规划从并行改成<b>串行</b> —— 那是牺牲吞吐换联动，不可接受。</p>
 *
 * <p>本类把「联动」从执行顺序里抽出来，改成一个<b>与顺序无关</b>的纯函数：</p>
 * <ol>
 *   <li>第一遍并行规划，每条路只交出一个 {@link CrossingPoint}（水上跨度的中心/宽度/水深）；</li>
 *   <li>本类把 XZ 距离 ≤ {@code clusterRadius} 的过河点聚成一簇，每簇选一个「共享过河点」；</li>
 *   <li>第二遍并行规划，每条路朝自己所属簇的共享点打折（点吸引）。</li>
 * </ol>
 *
 * <h2>确定性契约（四条，缺一条就会出「同一颗种子两台机器路不一样」）</h2>
 * <ul>
 *   <li><b>全序</b>：选点键是 {@code (widthBlocks 降序, waterDepth 降序, ownerKey 升序)}，
 *       前两项相同时才轮到 {@code ownerKey}；再相同（同一 ownerKey 出现两次）才用
 *       {@code x}/{@code z} 升序兜底。这个比较器是全序 ⇒ 最优者是唯一的。</li>
 *   <li><b>与输入顺序无关</b>：候选点先整体排序再聚簇，簇成员列表按同一次排序产出，
 *       簇列表按「簇内最优点的全局序号」升序 ⇒ 打乱输入得到逐字段相同的结果。</li>
 *   <li><b>单链接</b>：两点距离 ≤ 半径就并查集合并（传递性生效）。于是「两簇最近距离 &gt;
 *       半径」必然不合并 —— 这正是回归测试钉的那条。</li>
 *   <li><b>单条路不产生共享点</b>：只含一个点的簇<b>不返回</b>。一条路自己跟自己没什么好协商的，
 *       把它当成「共享点」会平白给它加一条优惠带（见 {@link #negotiate}）。
 *       0.9.058 起一条路可以交<b>多个</b>湿段，所以这条判据落在「至少两条<b>不同的路</b>」
 *       上（{@code distinctOwners}），而不是「至少两个点」。</li>
 *   <li><b>甲板也协商</b>（0.9.058）：{@code sharedDeckY} 取簇内成员 {@code deckY} 的最大值 ——
 *       max 天然与输入顺序无关，且是唯一既不会泡水、也不会在低侧留台阶的选择。</li>
 * </ul>
 *
 * <h2>纯度</h2>
 * <p>本类<b>不读世界、不读配置、没有可变静态状态</b>：输入只有过河点列表与半径，
 * 输出只有簇列表。唯一的时间复杂度是 O(n²) 的成对距离与 O(n·α(n)) 的并查集，
 * 一批连接只有几十条 ⇒ 微秒级。</p>
 */
public final class CrossingNegotiator {

    private CrossingNegotiator() {}

    /**
     * 一条路交出的「过河点」。
     *
     * @param ownerKey    交出它的那条路的稳定标识（同一批里唯一；用于全序的最后一档与回填）
     * @param x           水上跨度的中心 X
     * @param z           水上跨度的中心 Z
     * @param widthBlocks 水上跨度宽度（格）
     * @param waterDepth  水上跨度的最大水深（格）
     * @param deckY       这条路<b>自己</b>会在该处架起来的甲板高度
     *                    （有水 ⇒ 水面 + clearance；无水 ⇒ 两岸站 Y 的较大者 + clearance）。
     *                    协商层取簇内最大值作为 {@link Cluster#sharedDeckY()}，
     *                    于是簇内每条路都按同一个甲板压纵断面。未知时为
     *                    {@link Integer#MIN_VALUE}（此时不压）。
     */
    public record CrossingPoint(String ownerKey, int x, int z, int widthBlocks, int waterDepth, int deckY) {
        /** 兼容 5 参数调用（甲板未知）。 */
        public CrossingPoint(String ownerKey, int x, int z, int widthBlocks, int waterDepth) {
            this(ownerKey, x, z, widthBlocks, waterDepth, Integer.MIN_VALUE);
        }

        public CrossingPoint {
            ownerKey = ownerKey == null ? "" : ownerKey;
        }
    }

    /**
     * 一簇「同一条河上的同一个跨越」。
     *
     * @param sharedX     共享过河点 X（＝簇内排序键最优那个成员的 X）
     * @param sharedZ     共享过河点 Z（＝簇内排序键最优那个成员的 Z）
     * @param sharedDeckY 簇内一致认同的甲板高度（＝成员的 {@link CrossingPoint#deckY()} 最大值；
     *                    全簇都未知时为 {@link Integer#MIN_VALUE}）。取最大值而不是平均值：
     *                    甲板必须能同时盖住每个成员的水面/两岸高度，最大值是**唯一**既不泡水
     *                    也不会在低侧留台阶的选择，而且 max 天然与输入顺序无关。
     * @param members     簇内全部过河点，按最优优先排序（不可变）
     */
    public record Cluster(int sharedX, int sharedZ, int sharedDeckY, List<CrossingPoint> members) {
        /** 兼容 3 参数调用（甲板未知）。 */
        public Cluster(int sharedX, int sharedZ, List<CrossingPoint> members) {
            this(sharedX, sharedZ, Integer.MIN_VALUE, members);
        }

        public Cluster {
            members = List.copyOf(members == null ? List.of() : members);
        }
    }

    /**
     * 选点比较器：<b>最优在前</b>。
     *
     * <p>为什么是「宽度大 → 水深大 → ownerKey 小」：共享一座桥时，能把更宽的水面一次跨过去、
     * 水更深（更不可能只是路边积水）的那个位置，才是两条路都应该去用的位置；{@code ownerKey}
     * 只是把「前两项并列」这种退化情况钉成一个确定值，本身没有物理含义。</p>
     */
    private static final Comparator<CrossingPoint> BEST_FIRST =
            Comparator.comparingInt(CrossingPoint::widthBlocks).reversed()
                    .thenComparing(Comparator.comparingInt(CrossingPoint::waterDepth).reversed())
                    .thenComparing(CrossingPoint::ownerKey)
                    // 兜底：同一 ownerKey 重复出现时（正常流程不会发生）仍然要求全序。
                    .thenComparingInt(CrossingPoint::x)
                    .thenComparingInt(CrossingPoint::z);

    /**
     * 把过河点聚成「共享跨越」簇，并给每簇选一个共享过河点。
     *
     * <p>{@code points} 为 {@code null} 或空、或去空之后不足两条 ⇒ 返回空列表
     * （一条路不产生共享点）。只含一个成员的簇同样<b>不返回</b>：不构成「共享」，
     * 调用方对它必须保持逐位旧行为。</p>
     *
     * @param points        各条路交出的过河点，顺序任意（结果与顺序无关）
     * @param clusterRadius 聚簇半径（格，欧氏距离）；{@code ≤ 0} 时只有完全同点的过河点会合并
     * @return 只含「至少两条路」的簇，按簇内最优点的全局序号升序排列
     */
    public static List<Cluster> negotiate(List<CrossingPoint> points, int clusterRadius) {
        if (points == null || points.isEmpty()) return List.of();

        List<CrossingPoint> ordered = new ArrayList<>(points.size());
        for (CrossingPoint point : points) {
            if (point != null) ordered.add(point);
        }
        if (ordered.size() < 2) return List.of();

        // 先排成全序：这一步同时解决「簇成员顺序」「簇顺序」「每簇最优者」三件事。
        ordered.sort(BEST_FIRST);

        long reach = Math.max(0, clusterRadius);
        long limit = reach * reach;
        int[] parent = new int[ordered.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                if (squaredDistance(ordered.get(i), ordered.get(j)) <= limit) {
                    union(parent, i, j);
                }
            }
        }

        // 按「簇内最优点的全局序号」分组：LinkedHashMap 的插入序就是 i 的升序，
        // 于是簇顺序也是确定的，且与输入顺序无关。
        Map<Integer, List<CrossingPoint>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            grouped.computeIfAbsent(find(parent, i), root -> new ArrayList<>()).add(ordered.get(i));
        }

        List<Cluster> clusters = new ArrayList<>(grouped.size());
        for (List<CrossingPoint> members : grouped.values()) {
            if (members.size() < 2) continue;
            // 0.9.058：一条路现在会交出**多个**湿段，于是「两个成员」不再等于「两条路」。
            // 判据必须收回到「至少两条**不同的路**」：只含同一个 ownerKey 的簇是自
            // 己跟自己重合（同一条路两段水正好靠得近），不构成共享，必须丢弃。
            // 这一条同时保住了 0.9.057 的既有契约：单条路绝不因为自己有两个点就
            // 凭空多出一条优惠带、被第二遍重算一遍。
            if (distinctOwners(members) < 2) continue;
            CrossingPoint shared = members.get(0);
            // 0.9.058（缺陷 B）：甲板也要协商，不能只统一 XZ。
            // 成员按 BEST_FIRST 排过序，但 max 与顺序无关 —— 打乱输入仍得到同一个值。
            int sharedDeckY = Integer.MIN_VALUE;
            for (CrossingPoint member : members) {
                if (member.deckY() == Integer.MIN_VALUE) continue;
                sharedDeckY = sharedDeckY == Integer.MIN_VALUE
                        ? member.deckY() : Math.max(sharedDeckY, member.deckY());
            }
            clusters.add(new Cluster(shared.x(), shared.z(), sharedDeckY, members));
        }
        return List.copyOf(clusters);
    }

    /** 簇内不同 ownerKey 的个数（＝不同路的条数）。 */
    private static int distinctOwners(List<CrossingPoint> members) {
        java.util.Set<String> owners = new java.util.LinkedHashSet<>();
        for (CrossingPoint member : members) owners.add(member.ownerKey());
        return owners.size();
    }

    /**
     * 0.9.058（缺陷 B）：把一条路的纵断面**压到共享甲板**上。
     *
     * <p>缺陷：0.9.057 的协商只统一了 XZ（{@code sharedX/sharedZ}），两条路被拉到同一个
     * 过河点，甲板却各算各的 —— 真机同一条河上两座并行桥甲板 48 与 65，差 17 格，
     * 生成后处理器已经合不回来。这里补上「高度」那一半。</p>
     *
     * <h2>做法（受限的剖面压平）</h2>
     * <ol>
     *   <li>找到路径上离共享过河点最近的一站（Chebyshev 距离；并列取下标小的 ⇒ 全序）；</li>
     *   <li>以它为中心、半径 {@code flatStations} 的「平整核」直接放到 {@code sharedDeckY}；</li>
     *   <li>核外两侧各用一条**每站最多 1 格**的喇叭形约束
     *       {@code clamp(y, sharedDeckY - step, sharedDeckY + step)} 逼近原剖面，
     *       某一侧一旦整站不再变化（已汇入原剖面）或超出 {@code maxRampStations} 就停。</li>
     * </ol>
     *
     * <p>因此：① 相邻站高差永远 ≤ 1（不会造出 {@code MAX_HEIGHT_STEP} 违规）；
     * ② 只动
     * {@code 2 × (flatStations + maxRampStations)} 站以内的一小段，其余剖面一个字节不变；
     * ③ 纯函数、无随机、无世界读取，输入相同则输出逐字段相同。</p>
     *
     * @param path            第一遍/第二遍算出来的路径（不改原对象）
     * @param sharedX         共享过河点 X
     * @param sharedZ         共享过河点 Z
     * @param sharedDeckY     共享甲板高度；{@link Integer#MIN_VALUE} 表示未知 ⇒ 原样返回
     * @param flatStations    平整核半径（站）
     * @param maxRampStations 单侧最大引道站数（限幅，必须 &gt; 0 才压）
     */
    public static List<BlockPos> pressProfileToSharedDeck(List<BlockPos> path,
                                                          int sharedX,
                                                          int sharedZ,
                                                          int sharedDeckY,
                                                          int flatStations,
                                                          int maxRampStations) {
        if (path == null || path.isEmpty()) return path;
        if (sharedDeckY == Integer.MIN_VALUE) return path;
        if (maxRampStations <= 0) return path;

        int centerIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            BlockPos node = path.get(i);
            if (node == null) continue;
            int distance = Math.max(Math.abs(node.getX() - sharedX), Math.abs(node.getZ() - sharedZ));
            if (distance < bestDistance) {
                bestDistance = distance;
                centerIndex = i;
            }
        }
        if (centerIndex < 0) return path;

        BlockPos[] pressed = path.toArray(new BlockPos[0]);
        int flat = Math.max(0, flatStations);
        int flatEnd = Math.min(path.size() - 1, centerIndex + flat);
        boolean changed = false;
        for (int i = Math.max(0, centerIndex - flat); i <= flatEnd; i++) {
            if (path.get(i) == null) continue;
            changed |= writeY(pressed, i, sharedDeckY);
        }
        for (int direction = -1; direction <= 1; direction += 2) {
            for (int step = 1; step <= maxRampStations; step++) {
                int index = centerIndex + direction * (flat + step);
                if (index < 0 || index >= path.size()) break;
                BlockPos node = path.get(index);
                if (node == null) break;
                int low = sharedDeckY - step;
                int high = sharedDeckY + step;
                int clamped = Math.max(low, Math.min(high, node.getY()));
                if (clamped == node.getY()) break;   // 已汇入原剖面
                changed |= writeY(pressed, index, clamped);
            }
        }
        // 不去 List.copyOf：路径里理论上不该有 null，但压剖面不该因为别人的脏数据抛 NPE，
        // 所以原样保留元素（只换 Y），只求一份不可变视图。
        return changed ? java.util.Collections.unmodifiableList(java.util.Arrays.asList(pressed)) : path;
    }

    private static boolean writeY(BlockPos[] target, int index, int y) {
        BlockPos node = target[index];
        if (node == null || node.getY() == y) return false;
        target[index] = new BlockPos(node.getX(), y, node.getZ());
        return true;
    }

    /** 两点 XZ 欧氏距离的平方（{@code long} 承载，坐标差不会溢出）。 */
    private static long squaredDistance(CrossingPoint a, CrossingPoint b) {
        long dx = (long) a.x() - (long) b.x();
        long dz = (long) a.z() - (long) b.z();
        return dx * dx + dz * dz;
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) root = parent[root];
        // 路径压缩（原地、不影响最终划分，只省后面的查找）。
        int cursor = index;
        while (parent[cursor] != cursor) {
            int next = parent[cursor];
            parent[cursor] = root;
            cursor = next;
        }
        return root;
    }

    /** 并查集合并；小的序号当根，保证同一划分下根的选取也与输入顺序无关。 */
    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA == rootB) return;
        if (rootA < rootB) {
            parent[rootB] = rootA;
        } else {
            parent[rootA] = rootB;
        }
    }
}
