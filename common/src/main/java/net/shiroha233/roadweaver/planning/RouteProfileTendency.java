/* 文件职责：保存 AUTO 档按地形占比解析出的"路线级倾向"，仅影响该条路线。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-route terrain tendency resolved by the coarse AUTO profile
 * ({@code RawPathSearch} classifies the route and, when the global mode is
 * AUTO, stores TUNNEL_FIRST / BRIDGE_FIRST / BALANCED here). Consumers in the
 * exact generation phase (grade clamps via {@code RoadGradePlanner}, bridge
 * depth via {@code RoadSpanExtractor} call sites in {@code Road}) read the
 * tendency for their own connection only.
 *
 * <p>The tendency is deliberately NOT written back to {@link ConfigService}:
 * other routes keep the global mode and the persisted planning fingerprint
 * never sees the route-level override. Explicit TUNNEL_FIRST / BRIDGE_FIRST /
 * LIGHT_CUT selections ignore this registry entirely — {@link #effectiveMode}
 * returns the global mode for any non-AUTO selection.
 *
 * <p>Entries are overwritten on every planning pass of the same connection and
 * bounded by the number of live connections (one small enum each); a stale
 * entry after a route's terminal state is harmless because it is refreshed
 * before every retry wave and only consulted while the global mode is AUTO.
 */
public final class RouteProfileTendency {
    public enum Tendency { BALANCED, TUNNEL_FIRST, BRIDGE_FIRST }

    private static final ConcurrentHashMap<PlannedPathKey, Tendency> ACTIVE = new ConcurrentHashMap<>();

    private RouteProfileTendency() {}

    public static void set(StructureConnection connection, Tendency tendency) {
        if (connection == null || tendency == null) return;
        ACTIVE.put(PlannedPathKey.of(connection), tendency);
    }

    public static void clear(StructureConnection connection) {
        if (connection == null) return;
        ACTIVE.remove(PlannedPathKey.of(connection));
    }

    /** Drops every route-level tendency (cache clear / world change). */
    public static void clearAll() {
        ACTIVE.clear();
    }

    /**
     * The profile mode the exact generation phase should apply to this route:
     * the global mode when it is an explicit selection, otherwise the route's
     * AUTO tendency (BALANCED reads back as AUTO, which is the balanced
     * baseline).
     */
    public static PlanningConfig.TerrainProfileMode effectiveMode(StructureConnection connection) {
        PlanningConfig.TerrainProfileMode global =
                ConfigService.get().planning().terrainProfileMode();
        if (global != PlanningConfig.TerrainProfileMode.AUTO) return global;
        return switch (get(connection)) {
            case TUNNEL_FIRST -> PlanningConfig.TerrainProfileMode.TUNNEL_FIRST;
            case BRIDGE_FIRST -> PlanningConfig.TerrainProfileMode.BRIDGE_FIRST;
            case BALANCED -> PlanningConfig.TerrainProfileMode.AUTO;
        };
    }

    private static Tendency get(StructureConnection connection) {
        if (connection == null) return Tendency.BALANCED;
        Tendency tendency = ACTIVE.get(PlannedPathKey.of(connection));
        return tendency == null ? Tendency.BALANCED : tendency;
    }

    static Tendency peekForTest(StructureConnection connection) {
        return get(connection);
    }
}
