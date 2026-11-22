/* 文件职责：定义道路连接批次选择地形采样策略并产出原始路径的规划接口。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.core.model.StructureConnection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface RoadTerrainPlanningPort {
    Result plan(Request request);

    /** 两个开局生产入口显式登记；游玩期、全域精采和补线没有这次交接。 */
    enum InitialSolvePass { NONE, CAPTURE, REPLAY }

    record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public Bounds {
            if (maxX < minX || maxZ < minZ) {
                throw new IllegalArgumentException("invalid planning bounds");
            }
        }
    }

    record Request(ServerLevel level,
                   Bounds bounds,
                   List<StructureConnection> connections,
                   PathfindingCostConfig pathfinding,
                   BlockPos samplingCenter,
                   int samplingRadius,
                   InitialSolvePass initialSolvePass) {
        public Request(ServerLevel level,
                       Bounds bounds,
                       List<StructureConnection> connections,
                       PathfindingCostConfig pathfinding) {
            this(level, bounds, connections, pathfinding, null, 0, InitialSolvePass.NONE);
        }

        public Request(ServerLevel level, Bounds bounds, List<StructureConnection> connections,
                       PathfindingCostConfig pathfinding, BlockPos samplingCenter, int samplingRadius) {
            this(level, bounds, connections, pathfinding, samplingCenter, samplingRadius, InitialSolvePass.NONE);
        }

        public Request {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(bounds, "bounds");
            connections = List.copyOf(connections == null ? List.of() : connections);
            PathfindingCostConfig snapshot = Objects.requireNonNull(pathfinding, "pathfinding").snapshot();
            snapshot.sanitize();
            pathfinding = snapshot;
            samplingRadius = Math.max(0, samplingRadius);
            Objects.requireNonNull(initialSolvePass, "initialSolvePass");
        }

        public boolean hasCircularSamplingMask() {
            return samplingCenter != null && samplingRadius > 0;
        }
    }

    record Result(TerrainSamplingMode mode,
                  Map<StructureConnection, List<net.minecraft.core.BlockPos>> paths) {
        public Result {
            Objects.requireNonNull(mode, "mode");
            LinkedHashMap<StructureConnection, List<net.minecraft.core.BlockPos>> copy = new LinkedHashMap<>();
            if (paths != null) {
                paths.forEach((connection, path) -> {
                    if (connection != null && path != null && !path.isEmpty()) {
                        copy.put(connection, List.copyOf(path));
                    }
                });
            }
            paths = Map.copyOf(copy);
        }

        public static Result empty(TerrainSamplingMode mode) {
            return new Result(mode, Map.of());
        }
    }
}
