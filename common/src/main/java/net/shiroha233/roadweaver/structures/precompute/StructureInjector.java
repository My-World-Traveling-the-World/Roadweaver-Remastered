/* 文件职责：结构注入器。 */
package net.shiroha233.roadweaver.structures.precompute;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.structures.pieces.SimpleTemplatePiece;
import net.shiroha233.roadweaver.structures.types.RoadsideStructure;
import net.shiroha233.roadweaver.structures.types.RoadsideVillageStructure;
import net.shiroha233.roadweaver.structures.types.SpawnCabinStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 结构注入器
 */
public final class StructureInjector {
    /**
     * 被丢弃的待注入项累计数（2026-11-15 新增，纯取证）。
     *
     * <p><b>为什么需要它：</b>本类每次扫完一个区块都会调用
     * {@code markAsInjected(level, chunkPos)}，而该标记是<b>按区块</b>的、
     * 不是按单条的。于是「解析失败」「类型不支持」「没有产出任何部件」这几种
     * 情况下的待注入项，会在同一行标记里被一并清掉 —— <b>永久丢弃、不再重试</b>，
     * 而日志里只有一条 ERROR，事后没有任何汇总能看出总共丢了多少。</p>
     *
     * <p>这个计数只用来回答「到底丢了多少」，<b>不参与任何逻辑</b>。
     * 是否应该改成「全失败时不标记、保留重试」是另一个需要拍板的设计问题，
     * 本轮不动标记语义（动它会改变注入结果，必须真机验证）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong DROPPED_PENDING =
            new java.util.concurrent.atomic.AtomicLong();

    /** 被丢弃的待注入项累计数（只读取证；见字段 javadoc）。 */
    public static long droppedPendingCount() {
        return DROPPED_PENDING.get();
    }

    private StructureInjector() {}
    
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver/StructureInjector");
    
    public static void injectPendingStructures(ServerLevel level, ChunkAccess chunk) {
        if (level == null || chunk == null || !Level.OVERWORLD.equals(level.dimension())) return;
        injectSimplePendingStructures(level, chunk);
        injectRoadsideVillages(level, chunk);
    }

    private static void injectSimplePendingStructures(ServerLevel level, ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        List<PendingRoadsideStructure> pending = PendingStructureStorage.getPendingStructures(level, chunkPos);
        if (pending.isEmpty()) {
            return;
        }
        
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        StructureManager structureManager = level.structureManager();
        StructureTemplateManager templateManager = level.getStructureManager();
        
        injectBatch(pending, item -> structureRegistry.get(item.structureId()),
            (pendingStructure, structure) -> {
                SimpleTemplatePiece piece;
                if (structure instanceof RoadsideStructure roadsideStructure) {
                    piece = new SimpleTemplatePiece(
                        templateManager,
                        roadsideStructure.templateId(),
                        pendingStructure.anchor(),
                        pendingStructure.rotation(),
                        Mirror.NONE,
                        roadsideStructure.mobSpawns(),
                        roadsideStructure.lootConfigs()
                    );
                } else if (structure instanceof SpawnCabinStructure spawnCabin) {
                    piece = new SimpleTemplatePiece(
                        templateManager,
                        spawnCabin.templateId(),
                        pendingStructure.anchor(),
                        pendingStructure.rotation(),
                        Mirror.NONE
                    );
                } else {
                    LOGGER.warn("Structure {} is not a supported type, skipping", pendingStructure.structureId());
                    return false;
                }
                
                StructureStart start = new StructureStart(
                    structure,
                    chunkPos,
                    0,
                    new PiecesContainer(List.of(piece))
                );
                
                SectionPos sectionPos = SectionPos.of(chunkPos, 0);
                structureManager.setStartForStructure(sectionPos, structure, start, chunk);
                return true;
            }, item -> LOGGER.warn("Structure {} not found in registry, skipping", item.structureId()),
            (item, error) -> LOGGER.error("Failed to inject structure {} at {}", item.structureId(), item.anchor(), error),
            () -> PendingStructureStorage.markAsInjected(level, chunkPos), "structure", chunkPos);
    }

    private static void injectRoadsideVillages(ServerLevel level, ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        List<PendingRoadsideVillage> villages = PendingRoadsideVillageStorage.getPendingVillages(level, chunkPos);
        if (villages.isEmpty()) {
            return;
        }

        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        StructureManager structureManager = level.structureManager();
        StructureTemplateManager templateManager = level.getStructureManager();
        SectionPos sectionPos = SectionPos.of(chunkPos, 0);
        injectBatch(villages, item -> structureRegistry.get(item.structureId()),
            (village, structure) -> {
                if (!(structure instanceof RoadsideVillageStructure)) {
                    LOGGER.warn("Roadside village structure {} is not available", village.structureId());
                    return false;
                }

                List<PoolElementStructurePiece> pieces = RoadsideVillageJigsawPlanner.createPieces(level, village, templateManager);
                if (pieces.isEmpty()) {
                    LOGGER.debug("Roadside village {} produced no pieces", village.placementId());
                    return false;
                }

                StructureStart start = new StructureStart(
                    structure,
                    chunkPos,
                    0,
                    new PiecesContainer(new ArrayList<>(pieces))
                );
                structureManager.setStartForStructure(sectionPos, structure, start, chunk);
                return true;
            }, item -> LOGGER.warn("Roadside village structure {} is not available", item.structureId()),
            (item, error) -> LOGGER.error("Failed to inject roadside village {}", item.placementId(), error),
            () -> PendingRoadsideVillageStorage.markAsInjected(level, chunkPos), "roadside village", chunkPos);
    }

    static <T, S> void injectBatch(List<T> pending, Function<T, S> lookup, BiPredicate<T, S> inject,
                                  Consumer<T> missing, BiConsumer<T, Exception> failure,
                                  Runnable mark, String kind, ChunkPos chunkPos) {
        if (pending.isEmpty()) return;
        int injectedCount = 0;
        for (T item : pending) {
            try {
                S structure = lookup.apply(item);
                if (structure == null) {
                    missing.accept(item);
                } else if (inject.test(item, structure)) {
                    injectedCount++;
                }
            } catch (Exception e) {
                failure.accept(item, e);
            }
        }

        if (injectedCount > 0) {
            LOGGER.debug("Injected {} {}(s) into chunk [{}, {}]", injectedCount, kind, chunkPos.x, chunkPos.z);
        }
        // 标记会清掉整批；用应处理减成功覆盖所有拒绝，包括找不到注册 ID 的分支。
        finishInjection(mark, kind, chunkPos, injectedCount, pending.size() - injectedCount);
    }

    // 只有区块标记成功后才算永久丢弃；两条注入路径共用结算顺序以免口径走散。
    static void finishInjection(Runnable mark, String kind, ChunkPos chunkPos, int injectedCount, int droppedCount) {
        mark.run();
        if (droppedCount > 0) {
            DROPPED_PENDING.addAndGet(droppedCount);
            LOGGER.warn("RoadWeaver dropped {} pending {}(s) in chunk [{}, {}]"
                            + " — marked as injected, will NOT be retried (injected={})",
                    droppedCount, kind, chunkPos.x, chunkPos.z, injectedCount);
        }
    }
}
