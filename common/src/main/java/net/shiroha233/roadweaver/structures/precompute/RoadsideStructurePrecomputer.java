/* 文件职责：基于道路与共享精确地形预计算路边结构位置。 */
package net.shiroha233.roadweaver.structures.precompute;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.*;

import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.region.AccurateTerrainRegion;
import net.shiroha233.roadweaver.structures.data.BiomeCategory;
import net.shiroha233.roadweaver.structures.data.StructureScale;
import net.shiroha233.roadweaver.structures.registry.RoadsideStructureRegistry;
import net.shiroha233.roadweaver.structures.registry.RoadsideStructureRegistry.RoadsideStructureEntry;
import net.shiroha233.roadweaver.structures.types.RoadsideStructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 路边结构预计算器
 */
public final class RoadsideStructurePrecomputer {
    private RoadsideStructurePrecomputer() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver/StructurePrecomputer");

    public static int precomputeStructures(ServerLevel level,
                                           List<RoadSegmentPlacement> segments,
                                           List<RoadSpan> spans,
                                           int width,
                                           TerrainSamplingCache cache,
                                           PathTerrainField terrain,
                                           RandomSource random,
                                           List<Integer> targetY) {
        if (segments == null || segments.size() < RoadConstants.MIN_ROAD_SEGMENTS_FOR_STRUCTURE) {
            return 0;
        }

        ModConfig cfg = ConfigService.get();

        if (!cfg.roadsideStructure().enabled()) {
            return 0;
        }

        int maxStructures = cfg.roadsideStructure().maxStructuresPerRoad();
        if (maxStructures <= 0) {
            return 0;
        }

        List<RoadsideStructureEntry> allStructures = RoadsideStructureRegistry.getAll(level);
        if (allStructures.isEmpty()) {
            return 0;
        }

        Set<Integer> excludedIndices = new HashSet<>();
        if (spans != null) {
            java.util.Map<Long, Integer> spanIndices = new java.util.HashMap<>();
            for (int i = 0; i < segments.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return 0;
                BlockPos middle = segments.get(i).middlePos();
                if (middle != null) spanIndices.put(xzKey(middle), i);
            }
            for (RoadSpan span : spans) {
                if (Thread.currentThread().isInterrupted()) return 0;
                if (span.type() == SpanType.BRIDGE || span.type() == SpanType.ROAD_CROSSING
                        || span.type() == SpanType.TUNNEL || span.type() == SpanType.JUNCTION_ANCHOR
                        || span.type() == SpanType.TUNNEL_JUNCTION || span.type() == SpanType.NETWORK_REUSE
                        || span.type() == SpanType.PROTECTED_GAP) {
                    Integer start = spanIndices.get(xzKey(span.start()));
                    Integer end = spanIndices.get(xzKey(span.end()));
                    if (start == null || end == null) continue;
                    int buffer = span.type() == SpanType.TUNNEL ? 8 : 3;
                    int from = Math.max(0, Math.min(start, end) - buffer);
                    int to = Math.min(segments.size() - 1, Math.max(start, end) + buffer);
                    for (int i = from; i <= to; i++) {
                        excludedIndices.add(i);
                    }
                }
            }
        }

        int roadLength = segments.size();
        int checkInterval = Math.max(1, roadLength / (maxStructures + 1));

        Set<Long> placedChunks = new HashSet<>();
        int placedCount = 0;

        for (int i = checkInterval; i < roadLength - checkInterval && placedCount < maxStructures; i += checkInterval) {
            if (Thread.currentThread().isInterrupted()) return placedCount;
            if (excludedIndices.contains(i)) {
                continue;
            }

            BlockPos middle = segments.get(i).middlePos();

            int windowSize = RoadConstants.STRUCTURE_PLACEMENT_WINDOW_SIZE;
            BlockPos prev = segments.get(Math.max(0, i - windowSize)).middlePos();
            BlockPos next = segments.get(Math.min(roadLength - 1, i + windowSize)).middlePos();

            double dirX = next.getX() - prev.getX();
            double dirZ = next.getZ() - prev.getZ();
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len < 0.01) continue;
            dirX /= len;
            dirZ /= len;

            Holder<Biome> biomeHolder = cache.getBiome(level, middle.getX(), middle.getZ());
            BiomeCategory category = BiomeCategory.fromBiome(biomeHolder);

            RoadsideStructureEntry entry = selectStructure(allStructures, category, roadLength, random);
            if (entry == null) {
                continue;
            }

            RoadsideStructure structure = entry.structure();
            Vec3i sizeHint = structure.sizeHint();

            boolean leftSide = random.nextBoolean();
            int offset = getOffsetForScale(structure.scale(), cfg);

            Rotation rotation = calculateRotation(dirX, dirZ, leftSide, structure.faceRoad());

            double perpX = leftSide ? -dirZ : dirZ;
            double perpZ = leftSide ? dirX : -dirX;

            int sizeX = sizeHint.getX();
            int sizeZ = sizeHint.getZ();

            double halfExtentInPerpDir;
            switch (rotation) {
                case NONE, CLOCKWISE_180 -> halfExtentInPerpDir = (Math.abs(perpX) * sizeX + Math.abs(perpZ) * sizeZ) / 2.0;
                case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> halfExtentInPerpDir = (Math.abs(perpX) * sizeZ + Math.abs(perpZ) * sizeX) / 2.0;
                default -> halfExtentInPerpDir = Math.max(sizeX, sizeZ) / 2.0;
            }

            double centerOffset = offset + halfExtentInPerpDir;

            int placeX = middle.getX() + (int) Math.round(perpX * centerOffset);
            int placeZ = middle.getZ() + (int) Math.round(perpZ * centerOffset);

            // Get structure-defined start height offset mapped on heightmap
            WorldGenerationContext worldGenContext = new WorldGenerationContext(level.getChunkSource().getGenerator(), level);
            int startHeight = structure.startHeight().sample(random, worldGenContext);

            Heightmap.Types heightmap = structure.projectStartToHeightmap();
            boolean useBaseHeight = structure.useAccurateHeight();

            // Calculate baseHeight based on structure definition
            int baseHeight = (targetY != null && i < targetY.size()) ?
                targetY.get(i) :
                getHeight(placeX, placeZ, level, cache, terrain, heightmap, useBaseHeight);

            int placeY = baseHeight + startHeight;

            // The center of the structure. Used by terrain check.
            BlockPos center = new BlockPos(placeX, placeY, placeZ);

            // Calculate the structure anchor after rotation
            switch (rotation) {
                case NONE -> {
                    placeX -= sizeX / 2;
                    placeZ -= sizeZ / 2;
                }
                case CLOCKWISE_90 -> {
                    placeX += sizeZ / 2;
                    placeZ -= sizeX / 2;
                }
                case CLOCKWISE_180 -> {
                    placeX += sizeX / 2;
                    placeZ += sizeZ / 2;
                }
                case COUNTERCLOCKWISE_90 -> {
                    placeX -= sizeZ / 2;
                    placeZ += sizeX / 2;
                }
            }

            // The anchor of the structure placement. The placeX and placeZ value has been transformed by the
            // switch-case above.
            BlockPos anchor = new BlockPos(placeX, placeY, placeZ);
            ChunkPos chunkPos = new ChunkPos(anchor);

            long chunkKey = chunkPos.toLong();
            if (placedChunks.contains(chunkKey) ||
                    !structure.skipTerrainCheck() &&
                    !checkTerrainConditions(center, sizeHint, cache, terrain, level, heightmap, useBaseHeight)) {
                LOGGER.debug("【沿路结构未通过地形检查】 " + "Structure {} at {} failed the terrain check", entry.id(), anchor);
                continue;
            }

            // Push the precalculated data to pending queue
            PendingStructureStorage.addPendingStructure(
                level,
                entry.id(),
                anchor,
                rotation,
                sizeHint.getX(),
                sizeHint.getY(),
                sizeHint.getZ()
            );

            placedChunks.add(chunkKey);
            placedCount++;

            LOGGER.debug("【沿路结构预计算完成】 " + "Precomputed structure {} at {} for chunk [{}, {}]", entry.id(), anchor, chunkPos.x, chunkPos.z);
        }

        return placedCount;
    }

    private static RoadsideStructureEntry selectStructure(List<RoadsideStructureEntry> structures,
                                                          BiomeCategory category,
                                                          int roadLength,
                                                          RandomSource random) {
        List<RoadsideStructureEntry> candidates = new ArrayList<>();
        int totalWeight = 0;

        for (RoadsideStructureEntry entry : structures) {
            RoadsideStructure structure = entry.structure();

            if (!structure.placementRule().isBiomeAllowed(category)) {
                continue;
            }

            if (roadLength < structure.placementRule().minRoadLength()) {
                continue;
            }

            candidates.add(entry);
            totalWeight += structure.weight();
        }

        if (candidates.isEmpty() || totalWeight <= 0) {
            return null;
        }

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (RoadsideStructureEntry entry : candidates) {
            cumulative += entry.structure().weight();
            if (roll < cumulative) {
                return entry;
            }
        }

        return candidates.get(candidates.size() - 1);
    }

    /** 路边结构中心无水且四向边缘绝对高差不过阈值才候选；这是高差判据，不是除以距离的坡度。 */
    private static boolean checkTerrainConditions(
        BlockPos center,
        Vec3i size,
        TerrainSamplingCache cache,
        PathTerrainField terrain,
        ServerLevel level,
        Heightmap.Types heightmap,
        boolean useBaseHeight
    ) {
        int x = center.getX();
        int z = center.getZ();

        int y = getHeight(x, z, level, cache, terrain, heightmap, useBaseHeight);

        // Check if the center's column has water body on the surface
        if (hasAlignedTerrainSample(terrain, x, z)
                ? terrain.isColumnWater(x, z)
                : cache.isColumnWater(level, x, z)) {
            return false;
        }

        int halfX = size.getX() / 2;
        int halfZ = size.getZ() / 2;

        // Calculate the slope from the center to 4 directions (-X, +X, -Z, +Z)
        int maxSlope = RoadConstants.MAX_STRUCTURE_SLOPE;
        int y1 = getHeight(x - halfX, z, level, cache, terrain, heightmap, useBaseHeight);
        int y2 = getHeight(x + halfX, z, level, cache, terrain, heightmap, useBaseHeight);
        int y3 = getHeight(x, z - halfZ, level, cache, terrain, heightmap, useBaseHeight);
        int y4 = getHeight(x, z + halfZ, level, cache, terrain, heightmap, useBaseHeight);

        //LOGGER.debug("y={} y1={} y2={} y3={} y4={}", y, y1, y2, y3, y4);

        return Math.abs(y1 - y) <= maxSlope &&
               Math.abs(y2 - y) <= maxSlope &&
               Math.abs(y3 - y) <= maxSlope &&
               Math.abs(y4 - y) <= maxSlope;
    }

    private static int getOffsetForScale(StructureScale scale, ModConfig cfg) {
        return switch (scale) {
            case SMALL -> cfg.roadsideStructure().smallStructureOffset();
            case MEDIUM -> cfg.roadsideStructure().mediumStructureOffset();
            case LARGE -> cfg.roadsideStructure().largeStructureOffset();
        };
    }

    /** 快速地形高度不使用heightmap；精确原生getBaseHeight使用指定高度图，两者缓存与精度边界必须分清。 */
    private static int getHeight(int x,
                                 int z,
                                 ServerLevel level,
                                 TerrainSamplingCache cache,
                                 PathTerrainField terrain,
                                 Heightmap.Types heightmap,
                                 boolean useBaseHeight) {
        if (hasAlignedTerrainSample(terrain, x, z)) {
            return terrain.height(x, z);
        }
        if (!useBaseHeight) {
            return cache.height(level, x, z);
        }

        ServerChunkCache chunkSource = level.getChunkSource();
        return chunkSource.getGenerator().getBaseHeight(x, z, heightmap, level, chunkSource.getGeneratorState().randomState());
    }

    static boolean hasAlignedTerrainSample(PathTerrainField terrain, int x, int z) {
        return terrain instanceof AccurateTerrainRegion region && region.bounds().containsSample(x, z);
    }

    private static Rotation calculateRotation(double dirX, double dirZ, boolean leftSide, boolean faceRoad) {
        if (!faceRoad) {
            return Rotation.NONE;
        }

        double absX = Math.abs(dirX);
        double absZ = Math.abs(dirZ);

        if (absX > absZ) {
            if (leftSide) {
                return dirX > 0 ? Rotation.CLOCKWISE_180 : Rotation.NONE;
            } else {
                return dirX > 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
            }
        } else {
            if (leftSide) {
                return dirZ > 0 ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_90;
            } else {
                return dirZ > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
            }
        }
    }

    private static long xzKey(net.minecraft.core.BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
