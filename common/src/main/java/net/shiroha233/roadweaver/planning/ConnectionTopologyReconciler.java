package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prevents pending direct edges from duplicating an already completed network path. */
public final class ConnectionTopologyReconciler {
    private ConnectionTopologyReconciler() {
    }

    public static Result reconcile(List<StructureConnection> existing,
                                   List<StructureConnection> incoming) {
        LinkedHashMap<PlannedPathKey, StructureConnection> normalized = new LinkedHashMap<>();
        if (existing != null) {
            for (StructureConnection connection : existing) {
                if (!valid(connection)) continue;
                PlannedPathKey key = PlannedPathKey.of(connection);
                StructureConnection previous = normalized.get(key);
                if (previous == null || statusPriority(connection.status()) > statusPriority(previous.status())) {
                    normalized.put(key, connection);
                }
            }
        }

        Components completed = new Components();
        for (StructureConnection connection : normalized.values()) {
            if (connection.status() == ConnectionStatus.COMPLETED) {
                completed.join(connection.from(), connection.to());
            }
        }

        ArrayList<StructureConnection> retained = new ArrayList<>(normalized.size());
        ArrayList<StructureConnection> removed = new ArrayList<>();
        for (StructureConnection connection : normalized.values()) {
            if (isPending(connection.status())
                    && completed.connected(connection.from(), connection.to())) {
                removed.add(connection);
            } else {
                retained.add(connection);
            }
        }

        LinkedHashMap<PlannedPathKey, StructureConnection> merged = new LinkedHashMap<>();
        for (StructureConnection connection : retained) {
            merged.put(PlannedPathKey.of(connection), connection);
        }

        Components accepted = completed.copy();
        ArrayList<StructureConnection> added = new ArrayList<>();
        if (incoming != null) {
            for (StructureConnection candidate : incoming) {
                if (!valid(candidate)) continue;
                PlannedPathKey key = PlannedPathKey.of(candidate);
                if (merged.containsKey(key)) continue;
                if (accepted.connected(candidate.from(), candidate.to())) continue;

                StructureConnection planned = new StructureConnection(
                        candidate.from(), candidate.to(), ConnectionStatus.PLANNED);
                merged.put(key, planned);
                added.add(planned);
                accepted.join(planned.from(), planned.to());
            }
        }
        return new Result(List.copyOf(merged.values()), List.copyOf(removed), List.copyOf(added));
    }

    public static List<StructureConnection> filterNewConnections(
            List<StructureConnection> existing,
            List<StructureConnection> candidates) {
        return reconcile(existing, candidates).added();
    }

    private static boolean valid(StructureConnection connection) {
        return connection != null && connection.from() != null && connection.to() != null;
    }

    private static boolean isPending(ConnectionStatus status) {
        return status == ConnectionStatus.PLANNED || status == ConnectionStatus.GENERATING;
    }

    private static int statusPriority(ConnectionStatus status) {
        if (status == null) return 0;
        return switch (status) {
            case COMPLETED -> 4;
            case PROTECTED_GAP -> 3;
            case GENERATING -> 3;
            case PLANNED -> 2;
            case FAILED -> 1;
            // 规划前过滤的连接不再有任何待办工作，优先级等同失败（最低）。
            case FILTERED -> 0;
        };
    }

    public record Result(List<StructureConnection> connections,
                         List<StructureConnection> removed,
                         List<StructureConnection> added) {
        public boolean changed() {
            return !removed.isEmpty() || !added.isEmpty();
        }
    }

    private static final class Components {
        private final Map<Long, Long> parent;

        Components() {
            this(new HashMap<>());
        }

        private Components(Map<Long, Long> parent) {
            this.parent = parent;
        }

        Components copy() {
            return new Components(new HashMap<>(parent));
        }

        void join(BlockPos first, BlockPos second) {
            long firstKey = PlanningUtils.pos2dKey(first);
            long secondKey = PlanningUtils.pos2dKey(second);
            long firstRoot = root(firstKey);
            long secondRoot = root(secondKey);
            if (firstRoot != secondRoot) parent.put(firstRoot, secondRoot);
        }

        boolean connected(BlockPos first, BlockPos second) {
            long firstKey = PlanningUtils.pos2dKey(first);
            long secondKey = PlanningUtils.pos2dKey(second);
            if (firstKey == secondKey) return true;
            if (!parent.containsKey(firstKey) || !parent.containsKey(secondKey)) return false;
            return root(firstKey) == root(secondKey);
        }

        private long root(long key) {
            long current = parent.computeIfAbsent(key, ignored -> key);
            while (current != parent.get(current)) {
                current = parent.get(current);
            }
            long root = current;
            current = key;
            while (current != parent.get(current)) {
                long next = parent.get(current);
                parent.put(current, root);
                current = next;
            }
            return root;
        }
    }
}
