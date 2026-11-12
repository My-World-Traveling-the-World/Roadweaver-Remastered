/* 文件职责：计算可复用的世界生成持久化指纹。 */
package net.shiroha233.roadweaver.persistence;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryOps;
import net.minecraft.core.HolderLookup;

/**
 * 世界生成指纹计算服务。
 */
public final class WorldgenFingerprintService {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final String DOMAIN = "roadweaver:worldgen_fingerprint";

    /**
     * 世界生成指纹对同一 ServerLevel 而言是严格不变量(seed/generator/
     * dimension/dataVersion 全程不变),但每次计算都要用 CODEC 完整编码噪声
     * 设置并递归序列化成 NBT,再 SHA-256——这是 CPU 密集 + 深度递归的重活。
     *
     * 历史上它被主线程(onEndTick 的 RoadGenerationService.refreshQueue →
     * PlannedPathCache.find)反复调用,且 PlannedPathCache.find 在查内存缓存
     * 之前就无条件重算指纹,导致主线程每个 tick 都背一次全量序列化,tick
     * 债务单调累积、生物冻结(jstack 栈顶直指 Codec.encode 递归)。这里按
     * (level, schemaVersion) 缓存结果;key 用 ServerLevel 的 IdentityHashMap
     * 避免 equals/hashCode 开销与误合并,值按 schema 区分(同 level 不同
     * schema 结果不同)。指纹算法与 schemaVersion 保持不变,存量 planned_paths
     * 磁盘缓存全部继续有效。
     */
    private static final Map<ServerLevel, ConcurrentHashMap<Integer, WorldgenFingerprint>> CACHE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private WorldgenFingerprintService() {}

    public static WorldgenFingerprint forLevel(ServerLevel level, int schemaVersion) {
        Objects.requireNonNull(level, "level");
        ConcurrentHashMap<Integer, WorldgenFingerprint> bySchema;
        synchronized (CACHE) {
            bySchema = CACHE.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
        }
        return bySchema.computeIfAbsent(schemaVersion, v -> doCompute(level, v));
    }

    private static WorldgenFingerprint doCompute(ServerLevel level, int schemaVersion) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        Holder<NoiseGeneratorSettings> settingsHolder = generator instanceof NoiseBasedChunkGenerator noiseGenerator
                ? noiseGenerator.generatorSettings()
                : null;
        return create(
                level.dimension().location(),
                level.getSeed(),
                generatorIdentity(generator),
                level.registryAccess(),
                settingsHolder,
                SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                schemaVersion);
    }

    /** 维度卸载时清掉该 level 的缓存,避免残留。 */
    public static void clear(ServerLevel level) {
        if (level != null) {
            synchronized (CACHE) {
                CACHE.remove(level);
            }
        }
    }

    /** 服务端停止时清空全部缓存。 */
    public static void clearAll() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    public static WorldgenFingerprint forLevel(ServerLevel level) {
        return forLevel(level, CURRENT_SCHEMA_VERSION);
    }

    public static WorldgenFingerprint create(ResourceLocation dimension,
                                             long seed,
                                             String generatorIdentity,
                                             HolderLookup.Provider registries,
                                             Holder<NoiseGeneratorSettings> settingsHolder,
                                             int dataVersion,
                                             int schemaVersion) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(generatorIdentity, "generatorIdentity");
        Objects.requireNonNull(registries, "registries");

        var registryNbtOps = registries.createSerializationContext(NbtOps.INSTANCE);
        byte[] settingsIdentityBytes = settingsHolder == null
                ? new byte[0]
                : serialize(NoiseGeneratorSettings.CODEC.encodeStart(registryNbtOps, settingsHolder)
                .result()
                .orElseThrow(() -> new IllegalStateException("failed to encode noise settings identity")));
        byte[] settingsPayloadBytes = settingsHolder == null
                ? new byte[0]
                : serialize(NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(registryNbtOps, settingsHolder.value())
                .result()
                .orElseThrow(() -> new IllegalStateException("failed to encode noise settings payload")));

        Hasher hasher = Hashing.sha256().newHasher();
        putString(hasher, DOMAIN);
        hasher.putInt(schemaVersion);
        hasher.putInt(dataVersion);
        putString(hasher, dimension.toString());
        hasher.putLong(seed);
        putString(hasher, generatorIdentity);
        putBytes(hasher, settingsIdentityBytes);
        putBytes(hasher, settingsPayloadBytes);
        return new WorldgenFingerprint(hasher.hash().toString(), schemaVersion, dataVersion);
    }

    public static String generatorIdentity(ChunkGenerator generator) {
        Objects.requireNonNull(generator, "generator");
        String typeKey = generator.getTypeNameForDataFixer()
                .map(key -> key.location().toString())
                .orElse("unregistered");
        return typeKey + "|" + generator.getClass().getName();
    }

    private static void putString(Hasher hasher, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hasher.putInt(bytes.length);
        hasher.putBytes(bytes);
    }

    private static void putBytes(Hasher hasher, byte[] bytes) {
        hasher.putInt(bytes.length);
        hasher.putBytes(bytes);
    }

    private static byte[] serialize(Tag tag) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(raw)) {
                NbtIo.writeAnyTag(tag, out);
            }
            return raw.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("failed to serialize worldgen fingerprint payload", failure);
        }
    }
}
