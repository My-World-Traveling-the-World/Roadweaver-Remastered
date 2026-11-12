/* 文件职责：玩家手绘的一条路线。 */
package net.shiroha233.roadweaver.route;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * 玩家手绘的一条路线。
 *
 * <h2>生命周期</h2>
 * <pre>
 *   DRAFT     ← 正在画 / 正在调（可随意增删点、挪位、改高、撤销）
 *   READY     ← 玩家点了「确定」，等待施工
 *   BUILDING  ← 施工中
 *   DONE      ← 施工完成
 *   CANCELLED ← 玩家放弃
 * </pre>
 *
 * <p>只有 {@link RouteStatus#DRAFT} 允许编辑；一旦到 READY，
 * 说明玩家已经认可这条线，后续再改需要显式「重新编辑」（退回 DRAFT）。</p>
 *
 * <h2>为什么不复用 StructureConnection</h2>
 * {@code StructureConnection} 是「两点一条路」的**算法输入**，
 * 而手绘路线是**多点折线 + 每段类型 + 可编辑**，形状上就不一样。
 * 到注入阶段才把它拆成若干条 {@code StructureConnection}（或直接产出 RoadData）。</p>
 */
public record PlayerRoute(
        UUID id,
        String name,
        List<RoutePoint> points,
        RouteStatus status,
        long createdAtEpochMs,
        long updatedAtEpochMs
) {
    public static final Codec<PlayerRoute> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(PlayerRoute::id),
                    Codec.STRING.optionalFieldOf("name", "route").forGetter(PlayerRoute::name),
                    RoutePoint.CODEC.listOf().fieldOf("points").forGetter(PlayerRoute::points),
                    Codec.STRING.optionalFieldOf("status", RouteStatus.DRAFT.serializedName())
                            .xmap(RouteStatus::fromSerializedName, RouteStatus::serializedName)
                            .forGetter(PlayerRoute::status),
                    Codec.LONG.optionalFieldOf("created_at", 0L).forGetter(PlayerRoute::createdAtEpochMs),
                    Codec.LONG.optionalFieldOf("updated_at", 0L).forGetter(PlayerRoute::updatedAtEpochMs)
            ).apply(instance, PlayerRoute::new)
    );

    public PlayerRoute {
        points = List.copyOf(points == null ? List.of() : points);
        if (name == null || name.isBlank()) {
            name = "route";
        }
    }

    /** 新建一条空路线。 */
    public static PlayerRoute create(String name) {
        long now = System.currentTimeMillis();
        return new PlayerRoute(UUID.randomUUID(), name, List.of(), RouteStatus.DRAFT, now, now);
    }

    // ───────────────────────── 查询 ─────────────────────────

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** 至少要两个点才构成一段。 */
    public boolean isDrawable() {
        return points.size() >= 2;
    }

    public boolean isEditable() {
        return status == RouteStatus.DRAFT;
    }

    /** 段数 = 点数 - 1（点数 < 2 时为 0）。 */
    public int segmentCount() {
        return Math.max(0, points.size() - 1);
    }

    /** 第 {@code index} 段（从点 index 到点 index+1）的类型。 */
    public RouteKind kindOfSegment(int index) {
        if (index < 0 || index >= segmentCount()) {
            return RouteKind.ROAD;
        }
        return points.get(index).kind();
    }

    /** 路线起点（无点时返回 null）。 */
    public BlockPos start() {
        return points.isEmpty() ? null : points.get(0).pos();
    }

    /** 路线终点。 */
    public BlockPos end() {
        return points.isEmpty() ? null : points.get(points.size() - 1).pos();
    }

    public RoutePoint point(int index) {
        return points.get(index);
    }

    public int pointCount() {
        return points.size();
    }

    // ───────────────────── 返回新实例的编辑操作 ─────────────────────
    //
    // 全部是不可变风格：返回改了之后的副本。
    // 这样「撤销栈」只需要存历史副本，不需要存反向操作。

    private PlayerRoute withPoints(List<RoutePoint> newPoints) {
        return new PlayerRoute(id, name, newPoints, status, createdAtEpochMs,
                System.currentTimeMillis());
    }

    /** 末尾追加一个点；新段的类型取「上一个点的 kind」（延续当前工具条选择）。 */
    public PlayerRoute append(BlockPos pos, RouteKind kind) {
        List<RoutePoint> next = new ArrayList<>(points);
        next.add(new RoutePoint(pos, kind));
        return withPoints(next);
    }

    /** 在第 {@code index} 个点之后插入（用于在段中间加点）。 */
    public PlayerRoute insertAfter(int index, BlockPos pos) {
        if (index < 0 || index >= points.size()) {
            return append(pos, points.isEmpty() ? RouteKind.ROAD : points.get(points.size() - 1).kind());
        }
        List<RoutePoint> next = new ArrayList<>(points);
        next.add(index + 1, new RoutePoint(pos, points.get(index).kind()));
        return withPoints(next);
    }

    /** 删除第 {@code index} 个点。 */
    public PlayerRoute removePoint(int index) {
        if (index < 0 || index >= points.size()) {
            return this;
        }
        List<RoutePoint> next = new ArrayList<>(points);
        next.remove(index);
        return withPoints(next);
    }

    /** 替换第 {@code index} 个点（挪位 / 改高 / 改类型）。 */
    public PlayerRoute replacePoint(int index, RoutePoint point) {
        if (index < 0 || index >= points.size()) {
            return this;
        }
        List<RoutePoint> next = new ArrayList<>(points);
        next.set(index, point);
        return withPoints(next);
    }

    /** 改第 {@code index} 段的类型（即改第 index 个点的 kind）。 */
    public PlayerRoute withSegmentKind(int index, RouteKind kind) {
        if (index < 0 || index >= points.size()) {
            return this;
        }
        return replacePoint(index, points.get(index).withKind(kind));
    }

    /** 第 {@code index} 个点在屏幕/世界坐标下是否命中给定位置附近。 */
    public int findNearestPoint(BlockPos target, int maxDistance) {
        int best = -1;
        double bestDist = (double) maxDistance * maxDistance;
        for (int i = 0; i < points.size(); i++) {
            BlockPos p = points.get(i).pos();
            double dx = p.getX() - target.getX();
            double dy = p.getY() - target.getY();
            double dz = p.getZ() - target.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= bestDist) {
                bestDist = d2;
                best = i;
            }
        }
        return best;
    }

    /** 切换状态。 */
    public PlayerRoute withStatus(RouteStatus newStatus) {
        return new PlayerRoute(id, name, points, newStatus, createdAtEpochMs,
                System.currentTimeMillis());
    }

    public PlayerRoute withName(String newName) {
        return new PlayerRoute(id, newName, points, status, createdAtEpochMs,
                System.currentTimeMillis());
    }
}
