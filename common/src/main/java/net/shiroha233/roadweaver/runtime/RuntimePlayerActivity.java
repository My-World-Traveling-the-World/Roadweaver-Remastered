package net.shiroha233.roadweaver.runtime;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Keeps background road planning dormant while players are actively moving. */
public final class RuntimePlayerActivity {
    /** Keep gameplay background work at the safety floor for 5 minutes after movement. */
    private static final long SETTLE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final double MOVEMENT_THRESHOLD_SQUARED = 0.01D;
    private static final Map<UUID, Position> POSITIONS = new HashMap<>();
    private static volatile long activeUntilNanos;

    private RuntimePlayerActivity() {
    }

    public static synchronized void observe(MinecraftServer server) {
        if (server == null) {
            reset();
            return;
        }
        long now = System.nanoTime();
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null) continue;
            UUID id = player.getUUID();
            present.add(id);
            Position current = new Position(player.getX(), player.getZ());
            Position previous = POSITIONS.put(id, current);
            if (previous == null || previous.distanceSquared(current) >= MOVEMENT_THRESHOLD_SQUARED) {
                activeUntilNanos = Math.max(activeUntilNanos, now + SETTLE_NANOS);
            }
        }
        POSITIONS.keySet().removeIf(id -> !present.contains(id));
        if (present.isEmpty()) activeUntilNanos = 0L;
    }

    public static boolean isActive() {
        return System.nanoTime() < activeUntilNanos;
    }

    public static synchronized void reset() {
        POSITIONS.clear();
        activeUntilNanos = 0L;
    }

    private record Position(double x, double z) {
        private double distanceSquared(Position other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return dx * dx + dz * dz;
        }
    }
}
