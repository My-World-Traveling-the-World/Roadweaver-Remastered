/* 文件职责：带归属信息的结构包围盒，逐块精确碰撞判定转发给 PieceOccupancy。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.NonBlockingChunkFutureAccess;
import net.shiroha233.roadweaver.search.StructurePredictor;
import net.shiroha233.roadweaver.compat.SinglePoolElementTemplateAccess;
import net.shiroha233.roadweaver.compat.StructureTemplatePaletteAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.shiroha233.roadweaver.pathlogic.core.Road;

public record OwnedStructureBox(StructureOwnerKey owner, BoundingBox box, PieceOccupancy occupancy) {
    OwnedStructureBox(StructureOwnerKey owner, BoundingBox box) {
        this(owner, box, (PieceOccupancy) null);
    }

    OwnedStructureBox(StructureOwnerKey owner, BoundingBox box, Map<BlockPos, BlockState> testBlocks) {
        this(owner, box, PieceOccupancy.forTest(testBlocks));
    }

    BoundingBox collisionBox() {
        if (occupancy == null) return box;
        BoundingBox resolved = occupancy.collisionBounds();
        return resolved == null ? box : resolved;
    }

    boolean intersects(int x, int z, int minY, int maxY, int horizontalRadius) {
        if (box == null || x < box.minX() - horizontalRadius || x > box.maxX() + horizontalRadius
                || z < box.minZ() - horizontalRadius || z > box.maxZ() + horizontalRadius
                || maxY < box.minY() || minY > box.maxY()) return false;
        // A fallback box is metadata only. Planning deliberately ignores
        // it, so final validation must not turn it into a hard collision.
        if (occupancy == null) return false;
        Boolean exact = occupancy.intersects(x, z, minY, maxY, horizontalRadius);
        return Boolean.TRUE.equals(exact);
    }

    boolean intersectsSolid(int x, int z, int minY, int maxY) {
        if (box == null || x < box.minX() || x > box.maxX()
                || z < box.minZ() || z > box.maxZ()
                || maxY < box.minY() || minY > box.maxY()) return false;
        // Unknown occupancy is not proof of a real building block.
        if (occupancy == null) return false;
        Boolean exact = occupancy.intersectsSolid(x, z, minY, maxY);
        return Boolean.TRUE.equals(exact);
    }

    FallingCapOccupancy fallingCapOccupancyAt(int x, int y, int z) {
        if (box == null || x < box.minX() || x > box.maxX()
                || y < box.minY() || y > box.maxY()
                || z < box.minZ() || z > box.maxZ()) return FallingCapOccupancy.AIR;
        return occupancy == null
                ? FallingCapOccupancy.UNKNOWN
                : occupancy.fallingCapOccupancyAt(x, y, z);
    }

    String occupancyKind() {
        return occupancy == null ? "fallback-box" : occupancy.kind();
    }
}
