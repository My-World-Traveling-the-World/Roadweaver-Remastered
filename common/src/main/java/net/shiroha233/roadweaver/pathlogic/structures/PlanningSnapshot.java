/* 文件职责：结构避让的规划快照 —— 哪些结构盒会挡路、哪些端点归属者要豁免。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 这些成员仍留在 StructureAvoidanceService 里（搬迁时放宽到包内可见），
// 用静态导入引用，保证「纯搬迁、不改逻辑」—— 方法体一个字都不用动。
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.CONSTRUCTION_ABOVE;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.CONSTRUCTION_BELOW;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.ENDPOINT_REPLACEMENT_MATCH_BLOCKS;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.containsHorizontal;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.intersectsPlanningBand;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.isVillageStructure;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.horizontalDistanceSquared;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.encapsulate;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.endpointStructureFamily;

/**
 * 结构避让的规划快照。
 *
 * <h2>它是什么</h2>
 * <p>一条路在规划前先把「周围有哪些已验证建筑、哪些属于端点因此要豁免」固化下来，
 * 之后的每次判定都只读这份不可变快照 —— 这样同一条路在整段规划里看到的
 * 结构集合是<b>一致的</b>，不会因为中途区块加载状态变化而前后矛盾。</p>
 *
 * <h2>为什么它必须不可变</h2>
 * <p>规划会跨多个 worker 线程，快照按路线缓存、被多个线程共享。
 * 任何可变字段都会变成数据竞争。</p>
 *
 * <p>本类 714 行是 2026-11-12 从 4261 行的 {@code StructureAvoidanceService}
 * 里<b>纯搬迁</b>出来的，一行逻辑都没改。为了让搬迁可行，服务侧有 10 个原本
 * private 的成员放宽到了包内可见（5 个判定方法 + 4 个施工带常量 + LOGGER），
 * 以及 6 个伴随记录（RouteStartPlan 等）—— 它们仍留在服务里，只是不再 private。</p>
 */
public record PlanningSnapshot(Set<OwnedStructureBox> boxes,
                                 Map<Long, List<OwnedStructureBox>> boxesByChunk,
                                 Set<StructureOwnerKey> endpointOwners,
                                 Set<StructureOwnerKey> forcedVerticalOwners,
                                 Set<PreparedStartKey> resolvedStarts,
                                 LongSet coveredCandidateChunks,
                                 int roadRadius) {
    /** 与原外部类同名同 logger —— 搬迁不改变任何日志输出。 */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("roadweaver");

    public static final PlanningSnapshot EMPTY = new PlanningSnapshot(
            Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), LongSets.emptySet(), 0);

    static PlanningSnapshot forRoute(StructureConnection connection, Collection<OwnedStructureBox> boxes) {
        return forRoute(connection, boxes, Set.of());
    }

    static PlanningSnapshot forRoute(StructureConnection connection,
                                     Collection<OwnedStructureBox> boxes,
                                     Collection<PreparedStartKey> resolvedStarts) {
        return forRoute(connection, boxes, resolvedStarts, Set.of());
    }

    static PlanningSnapshot forRoute(StructureConnection connection,
                                     Collection<OwnedStructureBox> boxes,
                                     Collection<PreparedStartKey> resolvedStarts,
                                     Collection<Long> coveredCandidateChunks) {
        return forRoute(connection, boxes, resolvedStarts, coveredCandidateChunks, null);
    }

    static PlanningSnapshot forRoute(StructureConnection connection,
                                     Collection<OwnedStructureBox> boxes,
                                     Collection<PreparedStartKey> resolvedStarts,
                                     Collection<Long> coveredCandidateChunks,
                                     Collection<StructureOwnerKey> explicitEndpointOwners) {
        return build(connection, boxes, resolvedStarts, coveredCandidateChunks,
                explicitEndpointOwners, configuredRoadRadius(), null);
    }

    private static int configuredRoadRadius() {
        int width = Math.max(1, ConfigService.get().roadAppearance().roadWidth());
        // 栅格足迹包含偶数宽度道路的外侧列，桶半径必须与施工口径一致。
        return (int) Math.ceil(width / 2.0D);
    }

    private static PlanningSnapshot build(StructureConnection connection,
                                          Collection<OwnedStructureBox> boxes,
                                          Collection<PreparedStartKey> resolvedStarts,
                                          Collection<Long> coveredCandidateChunks,
                                          Collection<StructureOwnerKey> explicitEndpointOwners,
                                          int roadRadius, PlanningSnapshot reuse) {
        Set<PreparedStartKey> immutableStarts = resolvedStarts == null || resolvedStarts.isEmpty()
                ? Set.of()
                : Set.copyOf(resolvedStarts);
        LongSet immutableCoveredChunks;
        if (coveredCandidateChunks == null || coveredCandidateChunks.isEmpty()) {
            immutableCoveredChunks = LongSets.emptySet();
        } else {
            immutableCoveredChunks = LongSets.unmodifiable(new LongOpenHashSet(coveredCandidateChunks));
        }
        Set<StructureOwnerKey> immutableEndpointOwners = explicitEndpointOwners == null
                ? null
                : explicitEndpointOwners.isEmpty() ? Set.of() : Set.copyOf(explicitEndpointOwners);
        if (boxes == null || boxes.isEmpty()) {
            return new PlanningSnapshot(
                    Set.of(), Map.of(), immutableEndpointOwners == null ? Set.of() : immutableEndpointOwners,
                    Set.of(), immutableStarts, immutableCoveredChunks, roadRadius);
        }
        Set<OwnedStructureBox> immutableBoxes = Set.copyOf(boxes);
        Set<StructureOwnerKey> endpointOwners = immutableEndpointOwners == null
                ? findEndpointOwners(connection, immutableBoxes)
                : immutableEndpointOwners;
        endpointOwners = expandEndpointVillageOwners(connection, immutableBoxes, endpointOwners);
        // 同一批盒可改变端点归属，但归属不参与建桶；首个碰撞有含义，桶内顺序也需相同。
        Map<Long, List<OwnedStructureBox>> byChunk = reuse != null && reuse.roadRadius() == roadRadius
                && sameBoxOrder(immutableBoxes, reuse.boxes())
                ? reuse.boxesByChunk() : indexBoxes(immutableBoxes, roadRadius);
        return new PlanningSnapshot(immutableBoxes, byChunk, endpointOwners, Set.of(),
                immutableStarts, immutableCoveredChunks, roadRadius);
    }

    private static boolean sameBoxOrder(Set<OwnedStructureBox> a, Set<OwnedStructureBox> b) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        var previous = b.iterator();
        for (OwnedStructureBox box : a) if (box != previous.next()) return false;
        return true;
    }

    private static Map<Long, List<OwnedStructureBox>> indexBoxes(Set<OwnedStructureBox> boxes, int roadRadius) {
        Map<Long, List<OwnedStructureBox>> mutable = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.box() == null) continue;
            BoundingBox box = owned.box();
            for (int cx = (box.minX() - roadRadius) >> 4;
                 cx <= (box.maxX() + roadRadius) >> 4; cx++) {
                for (int cz = (box.minZ() - roadRadius) >> 4;
                     cz <= (box.maxZ() + roadRadius) >> 4; cz++) {
                    mutable.computeIfAbsent(ChunkPos.asLong(cx, cz), ignored -> new ArrayList<>()).add(owned);
                }
            }
        }
        Map<Long, List<OwnedStructureBox>> immutable = new HashMap<>();
        for (Map.Entry<Long, List<OwnedStructureBox>> entry : mutable.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    static PlanningSnapshot merge(StructureConnection connection,
                                   PlanningSnapshot previous,
                                   Collection<OwnedStructureBox> added,
                                   Collection<PreparedStartKey> addedStarts,
                                   Collection<Long> addedCoveredChunks) {
        return merge(connection, previous, added, addedStarts, addedCoveredChunks, null);
    }

    static PlanningSnapshot merge(StructureConnection connection,
                                   PlanningSnapshot previous,
                                   Collection<OwnedStructureBox> added,
                                   Collection<PreparedStartKey> addedStarts,
                                   Collection<Long> addedCoveredChunks,
                                   Collection<StructureOwnerKey> explicitEndpointOwners) {
        if (previous == null || previous == EMPTY || previous.boxes().isEmpty()) {
            Set<PreparedStartKey> starts = new HashSet<>();
            if (previous != null) starts.addAll(previous.resolvedStarts());
            if (addedStarts != null) starts.addAll(addedStarts);
            LongSet coveredChunks = new LongOpenHashSet();
            if (previous != null) coveredChunks.addAll(previous.coveredCandidateChunks());
            if (addedCoveredChunks != null) {
                for (long chunk : addedCoveredChunks) coveredChunks.add(chunk);
            }
            PlanningSnapshot created = forRoute(
                    connection, added, starts, coveredChunks, explicitEndpointOwners);
            if (previous != null && !previous.forcedVerticalOwners().isEmpty()) {
                return created.withForcedVerticalOwners(previous.forcedVerticalOwners());
            }
            return created;
        }
        boolean boxesUnchanged = added == null || added.isEmpty() || previous.boxes().containsAll(added);
        boolean startsUnchanged = addedStarts == null || addedStarts.isEmpty()
                || previous.resolvedStarts().containsAll(addedStarts);
        boolean coveredChunksUnchanged = addedCoveredChunks == null || addedCoveredChunks.isEmpty()
                || previous.coveredCandidateChunks().containsAll(addedCoveredChunks);
        boolean endpointOwnersUnchanged = explicitEndpointOwners == null
                || previous.endpointOwners().equals(Set.copyOf(explicitEndpointOwners));
        int roadRadius = configuredRoadRadius();
        // 配置路宽变化即使没解析出新结构，也会改变哪些近邻列必须参与保护。
        if (boxesUnchanged && startsUnchanged && coveredChunksUnchanged && endpointOwnersUnchanged
                && previous.roadRadius() == roadRadius) {
            return previous;
        }
        Set<OwnedStructureBox> merged = new HashSet<>(previous.boxes());
        if (added != null) merged.addAll(added);
        Set<PreparedStartKey> mergedStarts = new HashSet<>(previous.resolvedStarts());
        if (addedStarts != null) mergedStarts.addAll(addedStarts);
        LongSet mergedCoveredChunks = new LongOpenHashSet(previous.coveredCandidateChunks());
        if (addedCoveredChunks != null) {
            for (long chunk : addedCoveredChunks) mergedCoveredChunks.add(chunk);
        }
        PlanningSnapshot result = build(connection, merged, mergedStarts, mergedCoveredChunks,
                explicitEndpointOwners, roadRadius, boxesUnchanged ? previous : null);
        return result.withForcedVerticalOwners(previous.forcedVerticalOwners());
    }

    boolean blocks(BlockPos pos) {
        return blocks(pos, roadRadius);
    }

    boolean blocksFootprint(BlockPos pos) {
        return blocks(pos, 0);
    }

    /**
     * Endpoint structures are excluded from ordinary avoidance so a road
     * can reach them. Endpoint trimming needs the opposite query: only a
     * decoded real building inside an endpoint structure should stop the
     * final approach.
     */
    boolean blocksEndpointBuildingFootprint(BlockPos pos) {
        if (pos == null) return false;
        int minY = pos.getY() - CONSTRUCTION_BELOW;
        int maxY = pos.getY() + CONSTRUCTION_ABOVE;
        return findEndpointBuildingCollision(pos.getX(), pos.getZ(), minY, maxY, 0) != null;
    }

    boolean blocksSegmentFootprint(RoadSegmentPlacement segment) {
        if (segment == null) return false;
        BlockPos center = segment.middlePos();
        if (segment.positions() != null && !segment.positions().isEmpty()) {
            for (BlockPos footprint : segment.positions()) {
                if (footprint == null) continue;
                BlockPos constructionPos = center == null
                        ? footprint
                        : new BlockPos(footprint.getX(), center.getY(), footprint.getZ());
                if (blocksFootprint(constructionPos)) return true;
            }
            return false;
        }
        return center != null && blocks(center);
    }

    boolean blocksEndpointBuildingSegmentFootprint(RoadSegmentPlacement segment) {
        if (segment == null) return false;
        BlockPos center = segment.middlePos();
        if (segment.positions() != null && !segment.positions().isEmpty()) {
            for (BlockPos footprint : segment.positions()) {
                if (footprint == null) continue;
                BlockPos constructionPos = center == null
                        ? footprint
                        : new BlockPos(footprint.getX(), center.getY(), footprint.getZ());
                if (blocksEndpointBuildingFootprint(constructionPos)) return true;
            }
            return false;
        }
        return center != null && blocksEndpointBuildingFootprint(center);
    }

    boolean hasUnresolvedSegmentFootprint(RoadSegmentPlacement segment) {
        if (segment == null) return false;
        BlockPos center = segment.middlePos();
        if (segment.positions() != null && !segment.positions().isEmpty()) {
            for (BlockPos footprint : segment.positions()) {
                if (footprint == null) continue;
                BlockPos constructionPos = center == null
                        ? footprint
                        : new BlockPos(footprint.getX(), center.getY(), footprint.getZ());
                if (hasUnresolvedBlock(constructionPos)) return true;
            }
            return false;
        }
        return center != null && hasUnresolvedBlock(center);
    }

    boolean hasUnresolvedBlock(BlockPos pos) {
        if (pos == null) return false;
        int minY = pos.getY() - CONSTRUCTION_BELOW;
        int maxY = pos.getY() + CONSTRUCTION_ABOVE;
        OwnedStructureBox owned = findCollision(pos.getX(), pos.getZ(), minY, maxY, 0);
        return owned != null && "fallback-box".equals(owned.occupancyKind());
    }

    boolean blocks(BlockPos pos, int horizontalRadius) {
        if (pos == null) return false;
        int minY = pos.getY() - CONSTRUCTION_BELOW;
        int maxY = pos.getY() + CONSTRUCTION_ABOVE;
        return findBuildingCollision(pos.getX(), pos.getZ(), minY, maxY, horizontalRadius) != null;
    }

    /** 村庄群聚合盒判定（端点结构豁免）。 */
    boolean blocksCluster(int x, int z, int minY, int maxY, int horizontalRadius) {
        Map<StructureOwnerKey, BoundingBox> clusters = clusterBoxes(this);
        if (clusters.isEmpty()) return false;
        int radius = Math.max(0, horizontalRadius);
        for (BoundingBox box : clusters.values()) {
            if (box.maxX() < x - radius || box.minX() > x + radius) continue;
            if (box.maxZ() < z - radius || box.minZ() > z + radius) continue;
            if (box.maxY() < minY || box.minY() > maxY) continue;
            return true;
        }
        return false;
    }

    public OwnedStructureBox findCollision(int x, int z, int minY, int maxY) {
        return findCollision(x, z, minY, maxY, 0);
    }

    public OwnedStructureBox findFallingCapCollision(int x, int z, int minY, int maxY) {
        if (boxesByChunk.isEmpty() || minY > maxY) return null;
        List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) return null;
        for (int y = minY; y <= maxY; y++) {
            boolean solidStop = false;
            for (OwnedStructureBox owned : candidates) {
                if (owned == null) continue;
                FallingCapOccupancy occupancy = owned.fallingCapOccupancyAt(x, y, z);
                if (ownsEndpoint(owned.owner())) {
                    // Endpoint structures are allowed to be modified, but
                    // an untouched solid voxel still physically stops the
                    // runtime falling-column scan.
                    solidStop |= occupancy == FallingCapOccupancy.SOLID;
                    continue;
                }
                if (occupancy == FallingCapOccupancy.REMOVABLE) return owned;
                solidStop |= occupancy == FallingCapOccupancy.SOLID;
            }
            if (solidStop) return null;
            if (y == Integer.MAX_VALUE) break;
        }
        return null;
    }

    OwnedStructureBox findCollision(int x, int z, int minY, int maxY, int horizontalRadius) {
        if (boxesByChunk.isEmpty()) return null;
        List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) return null;
        for (OwnedStructureBox owned : candidates) {
            if (owned == null || ownsEndpoint(owned.owner())) continue;
            if (forcedVerticalOwners.contains(owned.owner())) {
                // A forced-vertical obstacle blocks every band that can
                // reach its box. A band entirely below the box floor (an
                // underground tunnel passing under a surface structure)
                // only has to clear the foundation, which the ordinary
                // band-overlap test already guarantees; it must not be
                // hard-rerouted by the footprint (task 6).
                BoundingBox box = owned.box();
                if (box != null && maxY >= box.minY()
                        && owned.intersects(x, z, box.minY(), box.maxY(), horizontalRadius)) {
                    return owned;
                }
            } else if (intersectsPlanningBand(owned, x, z, minY, maxY, horizontalRadius)) {
                return owned;
            }
        }
        return null;
    }

    OwnedStructureBox findBuildingCollision(int x, int z, int minY, int maxY,
                                                     int horizontalRadius) {
        if (boxesByChunk.isEmpty()) return null;
        List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) return null;
        for (OwnedStructureBox owned : candidates) {
            if (!isVerifiedBuilding(owned) || ownsEndpoint(owned.owner())) continue;
            if (forcedVerticalOwners.contains(owned.owner())) {
                // Same underground-pass rule as findCollision: a band lying
                // entirely below the structure floor is judged by the
                // ordinary band overlap, not by the full footprint.
                BoundingBox box = owned.box();
                if (box != null && maxY >= box.minY()
                        && owned.intersects(x, z, box.minY(), box.maxY(), horizontalRadius)) return owned;
            } else if (intersectsPlanningBand(owned, x, z, minY, maxY, horizontalRadius)) {
                return owned;
            }
        }
        return null;
    }

    OwnedStructureBox findEndpointBuildingCollision(int x, int z, int minY, int maxY,
                                                             int horizontalRadius) {
        if (boxesByChunk.isEmpty()) return null;
        List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) return null;
        for (OwnedStructureBox owned : candidates) {
            if (!isVerifiedBuilding(owned) || !ownsEndpoint(owned.owner())) continue;
            if (intersectsPlanningBand(owned, x, z, minY, maxY, horizontalRadius)) return owned;
        }
        return null;
    }

    boolean ownsEndpoint(StructureOwnerKey owner) {
        return owner != null && endpointOwners.contains(owner);
    }

    /**
     * 端点 XZ 是否落在快照里任意结构（含建筑片）盒的 XZ 范围内。
     * 供规划前过滤使用：不涉及 Y 与施工高度带，只回答"这个坐标是不是
     * 处在某个已解析结构的水平范围里"。
     */
    boolean insideAnyStructureBox(int x, int z) {
        if (boxesByChunk.isEmpty()) return false;
        List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) return false;
        for (OwnedStructureBox owned : candidates) {
            if (owned == null || owned.box() == null) continue;
            if (insideXZ(owned.box(), x, z)) return true;
        }
        return false;
    }

    /**
     * 端点 XZ 所属的村庄 owner（聚合该村庄全部建筑片盒后判定 XZ 包含）。
     * 同一点落在多个村庄聚合盒内时返回 null（歧义，不参与过滤）。
     */
    StructureOwnerKey villageOwnerAt(int x, int z) {
        if (boxesByChunk.isEmpty()) return null;
        List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) return null;
        Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
        for (OwnedStructureBox owned : candidates) {
            if (owned == null || owned.owner() == null || owned.box() == null) continue;
            if (!isVillageStructure(owned.owner().structureId())) continue;
            aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        StructureOwnerKey unique = null;
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : aggregate.entrySet()) {
            if (!insideXZ(entry.getValue(), x, z)) continue;
            if (unique != null && !unique.equals(entry.getKey())) return null;
            unique = entry.getKey();
        }
        return unique;
    }

    String describeCollision(BlockPos pos) {
        if (pos == null) return "position=null";
        int minY = pos.getY() - CONSTRUCTION_BELOW;
        int maxY = pos.getY() + CONSTRUCTION_ABOVE;
        OwnedStructureBox owned = findCollision(pos.getX(), pos.getZ(), minY, maxY, roadRadius);
        if (owned == null) {
            return "position=" + pos + " collision=none endpointOwners=" + endpointOwners.size();
        }
        BoundingBox box = owned.box();
        String boxText = box == null ? "null" : "["
                + box.minX() + "," + box.minY() + "," + box.minZ() + " -> "
                + box.maxX() + "," + box.maxY() + "," + box.maxZ() + "]";
        return "position=" + pos + " band=" + minY + ".." + maxY
                + " owner=" + owned.owner() + " box=" + boxText
                + " occupancy=" + owned.occupancyKind()
                + " endpointOwner=" + ownsEndpoint(owned.owner());
    }

    String describeCollision(BlockPos from, BlockPos to) {
        if (from == null || to == null) return "edge=" + from + " -> " + to;
        double distance = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        int samples = Math.max(1, (int) Math.ceil(distance));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            BlockPos sample = new BlockPos(
                    (int) Math.round(from.getX() + (to.getX() - from.getX()) * t),
                    (int) Math.round(from.getY() + (to.getY() - from.getY()) * t),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t));
            int minY = sample.getY() - CONSTRUCTION_BELOW;
            int maxY = sample.getY() + CONSTRUCTION_ABOVE;
            if (findCollision(sample.getX(), sample.getZ(), minY, maxY, roadRadius) != null) {
                return "edge=" + from + " -> " + to + " " + describeCollision(sample);
            }
        }
        return "edge=" + from + " -> " + to + " collision=none endpointOwners=" + endpointOwners.size();
    }

    List<RoadSegmentPlacement> stopAtVillageBuilding(StructureConnection connection,
                                                     List<RoadSegmentPlacement> segments) {
        if (connection == null || segments == null || segments.size() < 3
                || endpointOwners.isEmpty() || boxes.isEmpty()) return segments;
        BlockPos firstCenter = segments.getFirst().middlePos();
        BlockPos lastCenter = segments.getLast().middlePos();
        List<OwnedStructureBox> firstVillage = endpointVillageBoxes(
                closestEndpoint(connection, firstCenter, true));
        List<OwnedStructureBox> lastVillage = endpointVillageBoxes(
                closestEndpoint(connection, lastCenter, false));

        // Land each end at the structure-boundary interaction point: the
        // first station that exits the village's aggregate bounding box.
        // This stops the road at the village edge instead of running to its
        // centre (which drove tunnels into the mountain and flattened the
        // terrain inside the village).
        int first = firstOutsideVillageBox(segments, firstVillage, true);
        int last = firstOutsideVillageBox(segments, lastVillage, false);
        VillageBuildingCollision firstCollision = null;
        VillageBuildingCollision lastCollision = null;
        // Prefer landing at the structure boundary. Only fall back to the
        // first real building when the road never leaves the boundary box.
        if (first < 0) {
            firstCollision = firstVillageBuildingCollision(
                    segments, 0, 1, firstVillage);
            first = firstCollision == null ? 0 : firstCollision.segmentIndex() + 1;
        }
        if (last < 0) {
            lastCollision = firstVillageBuildingCollision(
                    segments, segments.size() - 1, -1, lastVillage);
            last = lastCollision == null
                    ? segments.size() - 1 : lastCollision.segmentIndex() - 1;
        }

        if (first == 0 && last == segments.size() - 1) return segments;
        if (first < 0 || last >= segments.size() || last - first + 1 < 2) {
            LOGGER.warn("RoadWeaver village building approach left no safe road connection={}->{} "
                            + "firstCollision={} lastCollision={}",
                    connection.from(), connection.to(), firstCollision, lastCollision);
            return null;
        }
        List<RoadSegmentPlacement> clipped = List.copyOf(segments.subList(first, last + 1));
        LOGGER.info("RoadWeaver landed at structure boundary connection={}->{} segments={} -> {} "
                        + "first={} last={} firstBuildingCollision={} lastBuildingCollision={}",
                connection.from(), connection.to(), segments.size(), clipped.size(),
                first, last, firstCollision, lastCollision);
        return clipped;
    }

    int firstOutsideVillageBox(List<RoadSegmentPlacement> segments,
                                       List<OwnedStructureBox> village,
                                       boolean fromStart) {
        if (segments == null || village == null || village.isEmpty()) return -1;
        BoundingBox box = aggregateStructureBox(village);
        if (box == null) return -1;
        if (fromStart) {
            for (int i = 0; i < segments.size(); i++) {
                BlockPos pos = segments.get(i).middlePos();
                if (pos != null && !insideXZ(box, pos.getX(), pos.getZ())) return i;
            }
        } else {
            for (int i = segments.size() - 1; i >= 0; i--) {
                BlockPos pos = segments.get(i).middlePos();
                if (pos != null && !insideXZ(box, pos.getX(), pos.getZ())) return i;
            }
        }
        return -1;
    }

    static boolean insideXZ(BoundingBox box, int x, int z) {
        return box != null && x >= box.minX() && x <= box.maxX()
                && z >= box.minZ() && z <= box.maxZ();
    }

    /**
     * T-23: 过路村庄聚合盒掩码。对每个"非端点 + 村庄类"owner 求聚合盒,
     * 站点 XZ 落入盒内且施工高度带([y-CONSTRUCTION_BELOW, y+CONSTRUCTION_ABOVE])
     * 与盒 Y 范围相交时标记为真 —— 即道路施工体积真正触及村庄 aggregated
     * 边界时才产生缺口,低空/深埋穿越不受影响。端点村庄(endpointOwners)
     * 被显式排除:它们由 stopAtVillageBuilding 的着陆逻辑处理,避免把真正
     * 的村庄端点裁剪掉(requirement 5)。
     */
    boolean[] passThroughVillageGapMask(List<BlockPos> points) {
        boolean[] mask = new boolean[points == null ? 0 : points.size()];
        try {
            if (!ConfigService.get().planning().passThroughVillageClipping()) return mask;
        } catch (Throwable ignored) {
            return mask;
        }
        Map<StructureOwnerKey, BoundingBox> passThrough = passThroughVillageBoxes();
        if (passThrough.isEmpty()) return mask;
        // 按用户口径：只要道路中心线在 XZ 上穿过"非端点村庄"的聚合包围盒，
        // 就按该盒把路裁开（站点落为保护缺口），不再要求施工高度带与盒 Y 相交。
        // 之前的 Y 判定会放过"从村子上/下方掠过"的路线，而剖面调整（坡度/洞口
        // 规划）之后它们又会落进建筑盒，只能等结构修复事后补救、绕不开就留断口。
        int clipped = 0;
        for (int i = 0; i < mask.length; i++) {
            BlockPos point = points.get(i);
            if (point == null) continue;
            for (BoundingBox box : passThrough.values()) {
                if (insideXZ(box, point.getX(), point.getZ())) {
                    mask[i] = true;
                    clipped++;
                    break;
                }
            }
        }
        if (clipped > 0) {
            LOGGER.info("RoadWeaver pass-through village clip: stations={}/{} villageBoxes={}",
                    clipped, mask.length, passThrough.size());
        }
        return mask;
    }

    Map<StructureOwnerKey, BoundingBox> passThroughVillageBoxes() {
        Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.owner() == null || owned.box() == null
                    || endpointOwners.contains(owned.owner())
                    || !isVillageStructure(owned.owner().structureId())) continue;
            aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        return aggregate;
    }

    static BoundingBox aggregateStructureBox(List<OwnedStructureBox> village) {
        if (village == null || village.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (OwnedStructureBox owned : village) {
            if (owned == null || owned.box() == null) continue;
            BoundingBox b = owned.box();
            minX = Math.min(minX, b.minX()); minY = Math.min(minY, b.minY()); minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX()); maxY = Math.max(maxY, b.maxY()); maxZ = Math.max(maxZ, b.maxZ());
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    List<OwnedStructureBox> endpointVillageBoxes(BlockPos endpoint) {
        if (endpoint == null) return List.of();
        Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.owner() == null || owned.box() == null
                    || !endpointOwners.contains(owned.owner())
                    || !isVillageStructure(owned.owner().structureId())) continue;
            aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        long maximumDistanceSquared = (long) ENDPOINT_REPLACEMENT_MATCH_BLOCKS
                * ENDPOINT_REPLACEMENT_MATCH_BLOCKS;
        long nearestDistance = Long.MAX_VALUE;
        Set<StructureOwnerKey> nearestOwners = new HashSet<>();
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : aggregate.entrySet()) {
            long distance = StructureAvoidanceService.horizontalDistanceSquared(entry.getValue(), endpoint);
            if (distance > maximumDistanceSquared) continue;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestOwners.clear();
                nearestOwners.add(entry.getKey());
            } else if (distance == nearestDistance) {
                nearestOwners.add(entry.getKey());
            }
        }
        if (nearestOwners.isEmpty()) return List.of();
        List<OwnedStructureBox> selected = new ArrayList<>();
        for (OwnedStructureBox owned : boxes) {
            if (owned != null && nearestOwners.contains(owned.owner())) selected.add(owned);
        }
        return List.copyOf(selected);
    }

    static BlockPos closestEndpoint(StructureConnection connection,
                                            BlockPos position,
                                            boolean preferFrom) {
        if (connection == null || position == null) return null;
        long fromDistance = horizontalDistanceSquared(connection.from(), position);
        long toDistance = horizontalDistanceSquared(connection.to(), position);
        if (fromDistance == toDistance) return preferFrom ? connection.from() : connection.to();
        return fromDistance < toDistance ? connection.from() : connection.to();
    }

    static long horizontalDistanceSquared(BlockPos left, BlockPos right) {
        if (left == null || right == null) return Long.MAX_VALUE;
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    static VillageBuildingCollision firstVillageBuildingCollision(
            List<RoadSegmentPlacement> segments,
            int start,
            int step,
            List<OwnedStructureBox> village) {
        if (segments == null || village == null || village.isEmpty() || step == 0) return null;
        for (int index = start; index >= 0 && index < segments.size(); index += step) {
            RoadSegmentPlacement segment = segments.get(index);
            if (segment == null || segment.middlePos() == null || segment.positions() == null) continue;
            int minY = segment.middlePos().getY() + 1;
            int maxY = segment.middlePos().getY() + CONSTRUCTION_ABOVE;
            for (BlockPos position : segment.positions()) {
                if (position == null) continue;
                for (OwnedStructureBox owned : village) {
                    if (owned != null && owned.intersectsSolid(
                            position.getX(), position.getZ(), minY, maxY)) {
                        return new VillageBuildingCollision(index, position, owned.owner(), owned.box());
                    }
                }
            }
        }
        return null;
    }

    record VillageBuildingCollision(int segmentIndex,
                                            BlockPos position,
                                            StructureOwnerKey owner,
                                            BoundingBox box) {
    }

    PlanningSnapshot forceVerticalOwner(StructureOwnerKey owner) {
        if (owner == null || ownsEndpoint(owner) || forcedVerticalOwners.contains(owner)) return this;
        Set<StructureOwnerKey> forced = new HashSet<>(forcedVerticalOwners);
        forced.add(owner);
        return withForcedVerticalOwners(forced);
    }

    PlanningSnapshot withForcedVerticalOwners(Collection<StructureOwnerKey> owners) {
        Set<StructureOwnerKey> forced = owners == null || owners.isEmpty() ? Set.of() : Set.copyOf(owners);
        return new PlanningSnapshot(boxes, boxesByChunk, endpointOwners, forced,
                resolvedStarts, coveredCandidateChunks, roadRadius);
    }

    PlanningSnapshot withAdditionalRoadRadius(int additionalRoadRadius) {
        if (additionalRoadRadius <= 0 || this == EMPTY) return this;
        int expandedRadius = roadRadius + additionalRoadRadius;
        if (expandedRadius == roadRadius) return this;
        // 扩路宽使用同一建桶口径，因为扩大半径后原桶边缘已不可复用。
        return new PlanningSnapshot(boxes, indexBoxes(boxes, expandedRadius), endpointOwners,
                forcedVerticalOwners, resolvedStarts, coveredCandidateChunks, expandedRadius);
    }

    static Set<StructureOwnerKey> findEndpointOwners(
            StructureConnection connection, Collection<OwnedStructureBox> boxes) {
        if (connection == null || boxes == null || boxes.isEmpty()) return Set.of();
        Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.owner() == null || owned.box() == null) continue;
            aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        Set<StructureOwnerKey> owners = new HashSet<>();
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : aggregate.entrySet()) {
            if (containsHorizontal(entry.getValue(), connection.from())
                    || containsHorizontal(entry.getValue(), connection.to())) {
                owners.add(entry.getKey());
            }
        }
        return Set.copyOf(owners);
    }

    static Set<StructureOwnerKey> expandEndpointVillageOwners(
            StructureConnection connection,
            Collection<OwnedStructureBox> boxes,
            Collection<StructureOwnerKey> current) {
        Set<StructureOwnerKey> expanded = new HashSet<>();
        if (current != null) expanded.addAll(current);
        if (connection == null || boxes == null || boxes.isEmpty() || expanded.isEmpty()) {
            return Set.copyOf(expanded);
        }
        // 端点村庄的家族名集合。Lithostitched 替换会把同一村庄拆成多个
        // owner(如 ctov:medium/village_dark_forest -> ctov:small/village_dark_forest),
        // 但这些拆分片的 family 相同;必须只聚合同 family 的村庄建筑片。
        // 旧实现按"距任一端点 <= 96 格"无条件聚合,会把两个相距 73 格但
        // 完全独立的村庄(如 biomeswevegone:village/salem 与
        // ctov:medium/village_dark_forest)误当成同一端点村庄群,导致过路村庄
        // 被豁免、道路整片切穿(T-35)。
        Set<String> endpointVillageFamilies = new HashSet<>();
        for (StructureOwnerKey owner : expanded) {
            if (owner != null && isVillageStructure(owner.structureId())) {
                endpointVillageFamilies.add(endpointStructureFamily(owner.structureId()));
            }
        }
        if (endpointVillageFamilies.isEmpty()) return Set.copyOf(expanded);
        long radiusSquared = (long) ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS
                * ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS;
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.owner() == null || owned.box() == null
                    || !isVerifiedBuilding(owned)
                    || !isVillageStructure(owned.owner().structureId())) continue;
            // 只聚合与端点村庄同 family 的建筑片,相邻独立村庄保持为过路障碍。
            if (!endpointVillageFamilies.contains(
                    endpointStructureFamily(owned.owner().structureId()))) continue;
            long fromDistance = StructureAvoidanceService.horizontalDistanceSquared(
                    owned.box(), connection.from());
            long toDistance = StructureAvoidanceService.horizontalDistanceSquared(
                    owned.box(), connection.to());
            if (Math.min(fromDistance, toDistance) <= radiusSquared) {
                expanded.add(owned.owner());
            }
        }
        return Set.copyOf(expanded);
    }
}
