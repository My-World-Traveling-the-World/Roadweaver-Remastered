/* 文件职责：结构道路偏移服务。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.search.StructurePredictor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构道路偏移服务
 */
public final class StructureRoadOffsetService {
    private StructureRoadOffsetService() {}

    private enum StructureCategory {
        VILLAGE,
        OTHER,
        UNKNOWN
    }

    private static final int MATCH_TOLERANCE_BLOCKS = 16;

    @Deprecated
    public static BlockPos adjustEndpoint(ServerLevel level, BlockPos endpoint, BlockPos otherEnd) {
        return endpoint;
    }

    public static List<RoadSegmentPlacement> trimPathNearStructure(
            ServerLevel level,
            List<RoadSegmentPlacement> segments,
            BlockPos rawStart,
            BlockPos rawEnd) {

        if (segments == null || segments.size() < 3) return segments;
        if (!Level.OVERWORLD.equals(level.dimension())) return segments;

        // Only villages participate in the boundary-intersection trim; spawn,
        // named structures, and unknown endpoints keep their own handling.
        BoundingBox startVillageBox = detectCategory(level, rawStart) == StructureCategory.VILLAGE
                ? villageBounds(level, rawStart) : null;
        BoundingBox endVillageBox = detectCategory(level, rawEnd) == StructureCategory.VILLAGE
                ? villageBounds(level, rawEnd) : null;
        int startOffset = startVillageBox == null ? getOffsetBlocksForEndpoint(level, rawStart) : 0;
        int endOffset = endVillageBox == null ? getOffsetBlocksForEndpoint(level, rawEnd) : 0;

        if (startOffset <= 0 && endOffset <= 0 && startVillageBox == null && endVillageBox == null) {
            return segments;
        }

        int n = segments.size();
        int trimStart = 0;
        int trimEnd = n;

        if (startVillageBox != null) {
            // A village endpoint trims the leading stations that stay inside
            // the village structure box; the road terminates at the first
            // station outside it (the road↔village boundary crossing).
            for (int i = 0; i < n; i++) {
                BlockPos pos = segments.get(i).middlePos();
                if (pos != null && !startVillageBox.isInside(pos)) {
                    trimStart = i;
                    break;
                }
            }
        } else if (startOffset > 0) {
            long offsetSq = (long) startOffset * startOffset;
            for (int i = 0; i < n; i++) {
                BlockPos pos = segments.get(i).middlePos();
                long dx = (long) pos.getX() - rawStart.getX();
                long dz = (long) pos.getZ() - rawStart.getZ();
                long distSq = dx * dx + dz * dz;
                if (distSq >= offsetSq) {
                    trimStart = i;
                    break;
                }
            }
        }

        if (endVillageBox != null) {
            for (int i = n - 1; i >= 0; i--) {
                BlockPos pos = segments.get(i).middlePos();
                if (pos != null && !endVillageBox.isInside(pos)) {
                    trimEnd = i + 1;
                    break;
                }
            }
        } else if (endOffset > 0) {
            long offsetSq = (long) endOffset * endOffset;
            for (int i = n - 1; i >= 0; i--) {
                BlockPos pos = segments.get(i).middlePos();
                long dx = (long) pos.getX() - rawEnd.getX();
                long dz = (long) pos.getZ() - rawEnd.getZ();
                long distSq = dx * dx + dz * dz;
                if (distSq >= offsetSq) {
                    trimEnd = i + 1;
                    break;
                }
            }
        }

        if (trimStart >= trimEnd || trimEnd - trimStart < 3) {
            // The boundary trim would consume the whole route (e.g. a short
            // village-to-village hop under two 60-block offsets). Shrink the
            // trim instead of keeping a detached middle slice: the route must
            // stay connected to its endpoints.
            int keep = Math.min(5, n);
            int trimmable = Math.max(0, n - keep);
            int startTrim = Math.min(trimStart, trimmable / 2);
            int endTrim = Math.min(n - trimEnd, trimmable - startTrim);
            trimStart = startTrim;
            trimEnd = n - endTrim;
            if (trimEnd - trimStart < 3) {
                trimStart = 0;
                trimEnd = n;
            }
        }

        return new ArrayList<>(segments.subList(trimStart, trimEnd));
    }

    /** 村庄端点返回实际结构包围盒；未加载时不阻塞取块，返回无盒而非主动生成。
 *
     * The village structure's actual {@link BoundingBox} for an endpoint, or
     * null when the endpoint is not a village or its chunk is not loaded.
     * Uses the chunk's resolved structure starts and pieces; {@code getChunkNow}
     * is non-blocking so this stays safe from worker threads.
     */
    private static BoundingBox villageBounds(ServerLevel level, BlockPos endpoint) {
        if (level == null || endpoint == null || !Level.OVERWORLD.equals(level.dimension())) return null;
        ChunkAccess chunk = level.getChunkSource().getChunkNow(endpoint.getX() >> 4, endpoint.getZ() >> 4);
        if (chunk == null) return null;
        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid()) continue;
            BoundingBox box = start.getBoundingBox();
            if (box != null && box.isInside(endpoint)) {
                return box;
            }
        }
        return null;
    }

    public static int getOffsetBlocksForEndpoint(ServerLevel level, BlockPos endpoint) {
        StructureCategory cat = detectCategory(level, endpoint);
        var cfg = ConfigService.get();
        return switch (cat) {
            // A road terminates at the village boundary crossing point, not
            // inside the built-up area. The village offset trims the route
            // outside the houses; the boundary grade then locks to that
            // crossing's real terrain height. StructureAvoidanceService still
            // clips against the rasterized footprint as a second guard.
            case VILLAGE -> cfg.villageRoadOffset();
            case OTHER -> cfg.otherStructureRoadOffset();
            default -> 0;
        };
    }

    private static StructureCategory detectCategory(ServerLevel level, BlockPos endpoint) {
        if (!Level.OVERWORLD.equals(level.dimension())) return StructureCategory.UNKNOWN;

        int cx = endpoint.getX() >> 4;
        int cz = endpoint.getZ() >> 4;
        int searchRadius = 1;
        int tolerance = Math.max(MATCH_TOLERANCE_BLOCKS, ConfigService.get().aStarStep() + 8);
        long tol2 = (long) tolerance * (long) tolerance;

        List<StructureInfo> villageInfos = StructurePredictor.predictOverworldStructuresInRect(
                level,
                cx - searchRadius, cz - searchRadius,
                cx + searchRadius, cz + searchRadius,
                true,
                List.of("#minecraft:village"),
                List.of()
        );
        if (villageInfos != null && !villageInfos.isEmpty()) {
            for (StructureInfo info : villageInfos) {
                BlockPos p = info.pos();
                long dx = (long) p.getX() - endpoint.getX();
                long dz = (long) p.getZ() - endpoint.getZ();
                if (dx * dx + dz * dz <= tol2) {
                    return StructureCategory.VILLAGE;
                }
            }
        }

        var cfg = ConfigService.get();
        List<String> whitelist = cfg.structureWhitelist();
        if (whitelist != null && !whitelist.isEmpty()) {
            List<StructureInfo> otherInfos = StructurePredictor.predictOverworldStructuresInRect(
                    level,
                    cx - searchRadius, cz - searchRadius,
                    cx + searchRadius, cz + searchRadius,
                    true,
                    whitelist,
                    cfg.structureBlacklist()
            );
            if (otherInfos != null && !otherInfos.isEmpty()) {
                for (StructureInfo info : otherInfos) {
                    BlockPos p = info.pos();
                    long dx = (long) p.getX() - endpoint.getX();
                    long dz = (long) p.getZ() - endpoint.getZ();
                    if (dx * dx + dz * dz <= tol2) {
                        return StructureCategory.OTHER;
                    }
                }
            }
        }

        return StructureCategory.UNKNOWN;
    }
}
