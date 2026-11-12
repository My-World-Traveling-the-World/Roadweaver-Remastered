/* 文件职责：路边内容的延迟落盘：一直保存到它所属的区块收到结构起始点为止。 */
package net.shiroha233.roadweaver.persistence.files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.structures.precompute.PendingRoadsideStructure;
import net.shiroha233.roadweaver.structures.precompute.PendingRoadsideVillage;
import net.shiroha233.roadweaver.structures.precompute.PendingRoadsideVillageSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persists roadside content until its owning chunk receives the structure starts. */
public final class PendingRoadsideContentFileStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String CATEGORY = "pending_roadside_content";

    private PendingRoadsideContentFileStorage() {}

    public static List<PendingRoadsideStructure> loadStructures(ServerLevel level, long chunkKey) {
        StructureFile[] rows = read(level, "structures", chunkKey, StructureFile[].class);
        if (rows == null || rows.length == 0) return List.of();
        ArrayList<PendingRoadsideStructure> result = new ArrayList<>(rows.length);
        for (StructureFile row : rows) {
            PendingRoadsideStructure value = row == null ? null : row.toValue();
            if (value != null && value.chunkKey() == chunkKey) result.add(value);
        }
        return List.copyOf(result);
    }

    public static void saveStructures(ServerLevel level,
                                      long chunkKey,
                                      List<PendingRoadsideStructure> structures) {
        ArrayList<StructureFile> rows = new ArrayList<>();
        if (structures != null) {
            for (PendingRoadsideStructure structure : structures) {
                if (structure != null && structure.chunkKey() == chunkKey) {
                    rows.add(StructureFile.from(structure));
                }
            }
        }
        writeOrDelete(level, "structures", chunkKey, rows.toArray(StructureFile[]::new));
    }

    public static List<PendingRoadsideVillage> loadVillages(ServerLevel level, long chunkKey) {
        VillageFile[] rows = read(level, "villages", chunkKey, VillageFile[].class);
        if (rows == null || rows.length == 0) return List.of();
        ArrayList<PendingRoadsideVillage> result = new ArrayList<>(rows.length);
        for (VillageFile row : rows) {
            PendingRoadsideVillage value = row == null ? null : row.toValue();
            if (value != null && value.chunkKey() == chunkKey) result.add(value);
        }
        return List.copyOf(result);
    }

    public static void saveVillages(ServerLevel level,
                                    long chunkKey,
                                    List<PendingRoadsideVillage> villages) {
        ArrayList<VillageFile> rows = new ArrayList<>();
        if (villages != null) {
            for (PendingRoadsideVillage village : villages) {
                if (village != null && village.chunkKey() == chunkKey) {
                    rows.add(VillageFile.from(village));
                }
            }
        }
        writeOrDelete(level, "villages", chunkKey, rows.toArray(VillageFile[]::new));
    }

    private static <T> T read(ServerLevel level, String kind, long chunkKey, Class<T> type) {
        Path path = path(level, kind, chunkKey);
        if (!Files.isRegularFile(path)) return null;
        try {
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
        } catch (Exception failure) {
            FileStorageIO.quarantineCorrupt(path, LOGGER, "Quarantined corrupt pending roadside content");
            return null;
        }
    }

    private static void writeOrDelete(ServerLevel level, String kind, long chunkKey, Object rows) {
        Path path = path(level, kind, chunkKey);
        int count = rows instanceof Object[] array ? array.length : 0;
        try {
            if (count == 0) {
                Files.deleteIfExists(path);
            } else {
                FileStorageIO.writeStringAtomic(path, GSON.toJson(rows));
            }
        } catch (IOException failure) {
            LOGGER.warn("Could not persist pending roadside content kind={} chunk={}", kind, chunkKey, failure);
        }
    }

    private static Path path(ServerLevel level, String kind, long chunkKey) {
        return FileStoragePathResolver.categoryRoot(level, CATEGORY)
                .resolve(kind)
                .resolve(Long.toString(chunkKey) + ".json");
    }

    private record StructureFile(String structureId,
                                 int x,
                                 int y,
                                 int z,
                                 String rotation,
                                 int sizeX,
                                 int sizeY,
                                 int sizeZ) {
        static StructureFile from(PendingRoadsideStructure value) {
            return new StructureFile(value.structureId().toString(), value.anchor().getX(), value.anchor().getY(),
                    value.anchor().getZ(), value.rotation().name(), value.sizeX(), value.sizeY(), value.sizeZ());
        }

        PendingRoadsideStructure toValue() {
            try {
                ResourceLocation id = ResourceLocation.tryParse(structureId);
                if (id == null) return null;
                return new PendingRoadsideStructure(id, new BlockPos(x, y, z), Rotation.valueOf(rotation),
                        sizeX, sizeY, sizeZ);
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    private record VillageFile(String placementId,
                               String structureId,
                               int chunkX,
                               int chunkZ,
                               int windowStartIndex,
                               int windowEndIndex,
                               String style,
                               long seed,
                               int minX,
                               int minY,
                               int minZ,
                               int maxX,
                               int maxY,
                               int maxZ,
                               List<VillageSlotFile> slots) {
        static VillageFile from(PendingRoadsideVillage value) {
            ArrayList<VillageSlotFile> slotRows = new ArrayList<>();
            if (value.slots() != null) {
                for (PendingRoadsideVillageSlot slot : value.slots()) {
                    if (slot != null) slotRows.add(VillageSlotFile.from(slot));
                }
            }
            BoundingBox bounds = value.estimatedBounds();
            return new VillageFile(value.placementId().toString(), value.structureId().toString(),
                    value.originChunk().x, value.originChunk().z, value.windowStartIndex(), value.windowEndIndex(),
                    value.style().toString(), value.seed(), bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ(), List.copyOf(slotRows));
        }

        PendingRoadsideVillage toValue() {
            try {
                ResourceLocation placement = ResourceLocation.tryParse(placementId);
                ResourceLocation structure = ResourceLocation.tryParse(structureId);
                ResourceLocation villageStyle = ResourceLocation.tryParse(style);
                if (placement == null || structure == null || villageStyle == null) return null;
                ArrayList<PendingRoadsideVillageSlot> values = new ArrayList<>();
                if (slots != null) {
                    for (VillageSlotFile slot : slots) {
                        PendingRoadsideVillageSlot value = slot == null ? null : slot.toValue();
                        if (value != null) values.add(value);
                    }
                }
                return new PendingRoadsideVillage(placement, structure, new ChunkPos(chunkX, chunkZ),
                        windowStartIndex, windowEndIndex, villageStyle, seed,
                        new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ), List.copyOf(values));
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    private record VillageSlotFile(int roadIndex,
                                   String side,
                                   int x,
                                   int y,
                                   int z,
                                   String outward,
                                   String kind,
                                   int footprintRadius) {
        static VillageSlotFile from(PendingRoadsideVillageSlot value) {
            return new VillageSlotFile(value.roadIndex(), value.side().name(), value.anchor().getX(),
                    value.anchor().getY(), value.anchor().getZ(), value.outward().name(), value.kind().name(),
                    value.footprintRadius());
        }

        PendingRoadsideVillageSlot toValue() {
            try {
                return new PendingRoadsideVillageSlot(roadIndex,
                        PendingRoadsideVillageSlot.Side.valueOf(side), new BlockPos(x, y, z),
                        Direction.valueOf(outward), PendingRoadsideVillageSlot.SlotKind.valueOf(kind),
                        footprintRadius);
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }
}
