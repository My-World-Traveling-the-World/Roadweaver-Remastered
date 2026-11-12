package net.shiroha233.roadweaver.pathlogic.grade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-endpoint chosen construction height for unknown structure nodes.
 *
 * <p>A village or the shared spawn locks every incoming road to the endpoint's
 * precise surface height. An unknown node has no verified entrance, so the
 * first road to reach it chooses between "lock the structure surface" and
 * "descend onto the road's own feasible approach height" by simulating both
 * and picking the cheaper near-endpoint window; the choice is stored here and
 * every later road into the same node reuses it. If a later road cannot build
 * at the stored height it degrades to its own approach height with a warning.
 *
 * <p>Only unknown endpoints consult this registry; village and spawn endpoints
 * keep their existing surface-height lock and never read or write it.
 */
public final class UnknownEndpointHeightRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Map<Long, EndpointState> CHOSEN = new ConcurrentHashMap<>();
    /** Pre-planned agreed height, set before generation from all incoming routes. */
    /**
     * Pre-planned shared approach profile per unknown endpoint: element 0 is
     * the endpoint station itself and later elements run inward, one per
     * station, already flattened to one block per station.
     */
    private static final Map<Long, int[]> AGREED_PROFILE = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> AGREED = new ConcurrentHashMap<>();

    /** Evaluation window: the last N stations before the endpoint. */
    private static final int APPROACH_WINDOW = 48;
    /** Stations the endpoint height may be reached over (8..32 per contract). */
    private static final int APPROACH_ANCHOR = 16;
    private static final long STEP_PENALTY = 10_000L;

    private UnknownEndpointHeightRegistry() {}

    public static void clearAll() {
        CHOSEN.clear();
        AGREED.clear();
        AGREED_PROFILE.clear();
    }

    /**
     * Registry-aware lock height for an unknown endpoint. First road computes
     * and stores; later roads reuse, degrading to their own feasible height
     * (WARN) when the stored height is unbuildable for their approach.
     */
    public static int lockedHeight(long endpointKey,
                                   int structureSurfaceY,
                                   int[] terrainY,
                                   int[] profile,
                                   int endpointStation,
                                   RoadGradePlanner.SurfaceGradeLimits limits) {
        // A pre-planned agreed height (set before generation from every
        // incoming route) wins: all roads into this endpoint build to the same
        // negotiated low height instead of the first road locking a tall one.
        Integer agreed = AGREED.get(endpointKey);
        if (agreed != null) {
            return agreed;
        }
        // Multi-road negotiation: the endpoint height is driven down to the
        // lowest natural approach height seen by ANY road into this node, so
        // a high-side first road does not lock a tall surface and make a
        // low-side road build a sudden bridge. Every later road re-evaluates;
        // the endpoint only ever steps downward toward the lowest approach.
        int n = profile.length;
        if (n == 0) return structureSurfaceY;
        int station = Math.max(0, Math.min(n - 1, endpointStation));
        int anchorStation = station == 0
                ? Math.min(n - 1, APPROACH_ANCHOR)
                : Math.max(0, station - APPROACH_ANCHOR);
        int base = profile[anchorStation];
        EndpointState state = CHOSEN.computeIfAbsent(endpointKey, k -> new EndpointState());
        state.lowestBase = Math.min(state.lowestBase, base);
        state.chosen = Math.min(state.chosen == Integer.MIN_VALUE
                ? structureSurfaceY : state.chosen, structureSurfaceY);
        state.chosen = Math.min(state.chosen, state.lowestBase);
        LOGGER.info("RoadWeaver unknown endpoint height chosen={} structureSurfaceY={} "
                        + "approachBaseY={} lowestBase={} window={}",
                state.chosen, structureSurfaceY, base, state.lowestBase, APPROACH_WINDOW);
        return state.chosen;
    }

    /**
     * Pre-plans the agreed endpoint height before any road is generated. The
     * caller gathers every incoming route and passes the lowest natural
     * approach height, so every road into this unknown endpoint shares one
     * height regardless of generation order.
     */
    public static void preplan(long endpointKey, int height) {
        AGREED.put(endpointKey, height);
    }

    /**
     * Pre-plans the agreed endpoint height together with the shared gentle
     * approach profile. The profile merges every incoming route's
     * endpoint-side window into one flattened (one block per station) grade;
     * {@code sharedProfile[0]} must equal {@code height}. Pass {@code null}
     * to store the height only.
     */
    public static void preplan(long endpointKey, int height, int[] sharedProfile) {
        AGREED.put(endpointKey, height);
        if (sharedProfile != null && sharedProfile.length > 0) {
            AGREED_PROFILE.put(endpointKey, sharedProfile.clone());
        } else {
            AGREED_PROFILE.remove(endpointKey);
        }
    }

    /**
     * The pre-planned shared approach profile for this unknown endpoint
     * (element 0 = endpoint station, running inward), or {@code null} when
     * this endpoint has no pre-planned profile.
     */
    public static int[] lockedProfile(long endpointKey) {
        int[] profile = AGREED_PROFILE.get(endpointKey);
        return profile == null ? null : profile.clone();
    }

    /** Per-endpoint mutable negotiation state. */
    private static final class EndpointState {
        int chosen = Integer.MIN_VALUE;
        int lowestBase = Integer.MAX_VALUE;
    }

    /**
     * Picks the cheaper of the two candidates by simulating each pinned ramp
     * over the near-endpoint window and charging for cut/fill beyond the
     * configured limits plus every one-block-step violation. Ties prefer the
     * structure surface (the historical behavior).
     */
    static int chooseHeight(int structureSurfaceY,
                            int approachFeasibleY,
                            int[] terrainY,
                            int[] profile,
                            int endpointStation,
                            RoadGradePlanner.SurfaceGradeLimits limits) {
        long surfaceCost = approachCost(structureSurfaceY, terrainY, profile, endpointStation, limits);
        long feasibleCost = approachCost(approachFeasibleY, terrainY, profile, endpointStation, limits);
        return feasibleCost < surfaceCost ? approachFeasibleY : structureSurfaceY;
    }

    /**
     * The height the road can actually reach at the endpoint without an
     * impossible climb: the profile value {@code anchor} stations before the
     * endpoint, extended toward the structure surface by at most one block per
     * remaining station. "The high side cannot climb up" therefore resolves to
     * "the road comes down to what it can reach".
     */
    static int approachFeasibleY(int structureSurfaceY,
                                 int[] terrainY,
                                 int[] profile,
                                 int endpointStation,
                                 int anchor) {
        int n = profile.length;
        if (n == 0) return structureSurfaceY;
        int station = Math.max(0, Math.min(n - 1, endpointStation));
        int anchorStation = station == 0
                ? Math.min(n - 1, anchor)
                : Math.max(0, station - anchor);
        int base = profile[anchorStation];
        int distance = Math.abs(station - anchorStation);
        return Math.max(base - distance, Math.min(structureSurfaceY, base + distance));
    }

    /**
     * Construction cost of pinning the endpoint to {@code height}: the window
     * is ramped at one block per station (only lowering), then charged for
     * cut/fill beyond the configured limits and for every adjacent ordinary
     * pair that still exceeds the one-block step.
     */
    static long approachCost(int height,
                             int[] terrainY,
                             int[] profile,
                             int endpointStation,
                             RoadGradePlanner.SurfaceGradeLimits limits) {
        int n = profile.length;
        if (n == 0 || terrainY == null || terrainY.length != n) return 0L;
        int station = Math.max(0, Math.min(n - 1, endpointStation));
        int[] sim = profile.clone();
        sim[station] = height;
        int direction = station == 0 ? 1 : -1;
        for (int d = 1; station + direction * d >= 0 && station + direction * d < n; d++) {
            int index = station + direction * d;
            // Prefer-cut: the approach descends onto the pinned height, and
            // stations it cannot reach keep their profile (never raised).
            sim[index] = Math.min(sim[index], sim[index - direction] + 1);
        }
        long cost = 0L;
        int windowStart = station == 0 ? 0 : Math.max(0, n - 1 - APPROACH_WINDOW);
        int windowEnd = station == 0 ? Math.min(n - 1, APPROACH_WINDOW) : n - 1;
        int cutLimit = limits.cut(false);
        int fillLimit = limits.fill(false);
        for (int i = windowStart; i <= windowEnd; i++) {
            int cut = terrainY[i] - sim[i];
            if (cut > cutLimit) cost += cut - cutLimit;
            int fill = sim[i] - terrainY[i];
            if (fill > fillLimit) cost += fill - fillLimit;
            if (i > windowStart && Math.abs(sim[i] - sim[i - 1]) > 1) cost += STEP_PENALTY;
        }
        return cost;
    }

    static int registrySizeForTest() {
        return CHOSEN.size();
    }

    static void putForTest(long endpointKey, int height) {
        EndpointState state = new EndpointState();
        state.chosen = height;
        state.lowestBase = height;
        CHOSEN.put(endpointKey, state);
    }
}
