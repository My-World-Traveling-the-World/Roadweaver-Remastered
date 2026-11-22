/* 文件职责：按区块持久化道路标牌待写文本。 */
package net.shiroha233.roadweaver.persistence.files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 路牌待写文本文件存储：按 chunk 分片。
 */
public final class SignTextFileStorage {
    private SignTextFileStorage() {}

    private static final String CATEGORY = "sign_texts";
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final String FILE_SUFFIX = ".json";
    public static final int TYPE_DISTANCE = 0;
    public static final int TYPE_SEA_QUESTION = 1;

    public static record PendingSignText(long id, BlockPos pos, int signType, String payload, long updatedAt) {}
    public static record PendingSignWrite(BlockPos pos, int signType, String payload) {}

    public static synchronized void upsertBatch(ServerLevel level, Collection<PendingSignWrite> writes) {
        if (!isOverworld(level) || writes == null || writes.isEmpty()) return;
        Map<Long, List<PendingSignWrite>> byChunk = new HashMap<>();
        for (var write : writes) {
            if (write == null || write.pos() == null) continue;
            long key = ChunkPos.asLong(write.pos().getX() >> 4, write.pos().getZ() >> 4);
            byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(write);
        }
        byChunk.forEach((key, batch) -> mergeChunk(chunkFile(level, key), batch));
    }

    static void mergeChunk(Path file, Collection<PendingSignWrite> writes) {
        var rows = new LinkedHashMap<Long, PendingSignText>();
        for (var old : loadChunk(file)) rows.put(old.id(), old);
        for (var write : writes) {
            long id = write.pos().asLong();
            // 保留旧版“最后一次更新排在末尾”的顺序，但不再为每块牌扫整份分片。
            rows.remove(id);
            rows.put(id, new PendingSignText(id, write.pos(), write.signType(), write.payload() == null ? "" : write.payload(),
                    System.currentTimeMillis() / 1000L));
        }
        saveChunk(file, new ArrayList<>(rows.values()));
    }

    public static synchronized List<PendingSignText> queryByChunk(ServerLevel level, int chunkX, int chunkZ, int limit) {
        if (!isOverworld(level) || limit <= 0) return List.of();
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        List<PendingSignText> chunk = loadChunk(chunkFile(level, chunkKey));
        if (chunk.size() <= limit) return new ArrayList<>(chunk);
        return new ArrayList<>(chunk.subList(0, limit));
    }

    public static synchronized void deleteByIds(ServerLevel level, List<Long> ids) {
        if (!isOverworld(level) || ids == null || ids.isEmpty()) return;
        Map<Long, HashSet<Long>> byChunk = new HashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            BlockPos pos = BlockPos.of(id);
            long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            byChunk.computeIfAbsent(chunkKey, k -> new HashSet<>()).add(id);
        }
        for (var entry : byChunk.entrySet()) {
            Path file = chunkFile(level, entry.getKey());
            List<PendingSignText> chunk = loadChunk(file);
            chunk.removeIf(existing -> entry.getValue().contains(existing.id()));
            saveChunk(file, chunk);
        }
    }

    public static synchronized void clearLevel(ServerLevel level) {
        if (!isOverworld(level)) return;
        FileStorageIO.deleteTree(FileStoragePathResolver.categoryRoot(level, CATEGORY), null, "清理路牌文本文件失败");
    }

    public static synchronized void clearAllMemory() {}

    static List<PendingSignText> loadChunk(Path file) {
        // 先完成字节读取再解析，避免 Gson 把读盘失败包装成坏 JSON。
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            SignChunkData data = SignTextShardStorageCompat.GSON.fromJson(json, SignChunkData.class);
            if (data == null || data.items == null) throw new com.google.gson.JsonSyntaxException("缺少路牌列表");
            return new ArrayList<>(data.items);
        } catch (NoSuchFileException absent) {
            return new ArrayList<>();
        } catch (IOException temporarilyUnavailable) {
            throw new UncheckedIOException(temporarilyUnavailable);
        } catch (com.google.gson.JsonParseException corrupt) {
            FileStorageIO.quarantineCorrupt(file, LOGGER, "路牌文本分片损坏");
            // 隔离不成功也不能在原件上写入空表。
            if (!Files.notExists(file)) throw new UncheckedIOException(new IOException("无法隔离：" + file, corrupt));
            return new ArrayList<>();
        }
    }

    private static void saveChunk(Path file, List<PendingSignText> items) {
        try {
            FileStorageIO.writeStringAtomic(file, SignTextShardStorageCompat.GSON.toJson(new SignChunkData(items)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path chunkFile(ServerLevel level, long chunkKey) {
        Path root = FileStoragePathResolver.categoryRoot(level, CATEGORY);
        long x = (int) (chunkKey >> 32);
        long z = (int) chunkKey;
        return root.resolve(Long.toString(x)).resolve(Long.toString(z) + FILE_SUFFIX);
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }

    private static final class SignChunkData {
        List<PendingSignText> items;

        SignChunkData() {}
        SignChunkData(List<PendingSignText> items) { this.items = items == null ? new ArrayList<>() : new ArrayList<>(items); }
    }

    private static final class SignTextShardStorageCompat {
        private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    }
}
