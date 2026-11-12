/* 文件职责：在不生成区块的前提下预测结构位置 —— 靠结构集的种子算术，不读真实世界。
 *
 * 「不生成区块」是硬约束：搜索界面里预测几十个结构，一旦触发区块生成
 * 就会把主线程卡住；纯种子推算才能做到即时。
 */
package net.shiroha233.roadweaver.search;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

/**
 * 结构位置预测服务
 */
public final class StructurePredictor {
    /**
     * Structure biome predicates are evaluated in 3D.  Sampling only Y=64
     * rejects valid high-altitude structures such as CTOV mountain villages,
     * whose terrain surface can be far above that level.
     */
    private static final int[] BIOME_PREFILTER_SAMPLE_HEIGHTS = {
            0, 32, 64, 96, 128, 160, 192, 224, 256, 320, 384, 512, 768, 1024
    };

    private StructurePredictor() {
    }

    public static List<StructureInfo> predictStructuresInRect(ServerLevel level,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            boolean biomePrefilter,
            List<String> whitelist,
            List<String> blacklist) {
        if (!isOverworld(level)) return List.of();
        return predictOverworldStructuresInRect(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, biomePrefilter,
                whitelist, blacklist);
    }

    public static List<StructureInfo> predictOverworldVillagesAroundSpawn(ServerLevel level, int radiusChunks,
            boolean biomePrefilter) {
        if (!isOverworld(level)) return List.of();
        RegistryAccess registryAccess = level.registryAccess();
        Registry<StructureSet> setRegistry = registryAccess.registryOrThrow(Registries.STRUCTURE_SET);
        Optional<Holder.Reference<StructureSet>> optVillages = setRegistry.getHolder(BuiltinStructureSets.VILLAGES);
        if (optVillages.isEmpty())
            return List.of();
        StructureSet set = optVillages.get().value();
        StructurePlacement placement = set.placement();
        if (!(placement instanceof RandomSpreadStructurePlacement rssp))
            return List.of();

        BlockPos spawn = level.getSharedSpawnPos();
        int cx = spawn.getX() >> 4;
        int cz = spawn.getZ() >> 4;
        int minX = cx - radiusChunks;
        int maxX = cx + radiusChunks;
        int minZ = cz - radiusChunks;
        int maxZ = cz + radiusChunks;

        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomState randomState = state.randomState();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();

        Set<Holder<Biome>> allowedBiomes = null;
        if (biomePrefilter) {
            allowedBiomes = new HashSet<>();
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                Structure structure = entry.structure().value();
                for (Holder<Biome> b : structure.biomes()) {
                    allowedBiomes.add(b);
                }
            }
        }

        int spacing = rssp.spacing();
        int startI = Math.floorDiv(minX, spacing);
        int endI = Math.floorDiv(maxX, spacing);
        int startJ = Math.floorDiv(minZ, spacing);
        int endJ = Math.floorDiv(maxZ, spacing);

        long seed = level.getSeed();
        List<StructureInfo> result = new ArrayList<>();

        for (int i = startI; i <= endI; i++) {
            for (int j = startJ; j <= endJ; j++) {
                int baseX = i * spacing;
                int baseZ = j * spacing;
                ChunkPos candidate = rssp.getPotentialStructureChunk(seed, baseX, baseZ);
                int x = candidate.x;
                int z = candidate.z;
                if (x < minX || x > maxX || z < minZ || z > maxZ)
                    continue;
                if (!placement.isStructureChunk(state, x, z))
                    continue;

                BlockPos locatePos = placement.getLocatePos(candidate);
                if (biomePrefilter && allowedBiomes != null) {
                    int qx = QuartPos.fromBlock(locatePos.getX());
                    int qy = QuartPos.fromBlock(64);
                    int qz = QuartPos.fromBlock(locatePos.getZ());
                    Holder<Biome> sample = biomeSource.getNoiseBiome(qx, qy, qz, randomState.sampler());
                    if (!allowedBiomes.contains(sample))
                        continue;
                }

                result.add(new StructureInfo(locatePos, "village"));
            }
        }

        return result;
    }

    public static List<StructureInfo> predictOverworldStructuresInRect(ServerLevel level,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            boolean biomePrefilter,
            List<String> whitelist,
            List<String> blacklist) {
        if (!isOverworld(level)) return List.of();
        return predictStructuresInRectUnchecked(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                biomePrefilter, whitelist, blacklist, false);
    }

    /**
     * Predicts same-dimension structure-set candidates for route avoidance.
     * Keeping the originating set lets the exact resolver run Minecraft's
     * native structure selection without generating a real chunk.
     */
    public static List<AvoidanceStructureCandidate> predictAvoidanceStructuresInRect(ServerLevel level,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ) {
        if (!isOverworldDimension(level)) return List.of();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        List<AvoidanceStructureCandidate> result = new ArrayList<>();

        for (Holder<StructureSet> holder : state.possibleStructureSets()) {
            // These sets are either dimension-incompatible or intentionally
            // underground. Resolving tens of thousands of them adds no
            // protection for a surface road and can dominate startup time.
            if (!isSurfaceRoadRelevant(holder)) continue;
            StructurePlacement placement = holder.value().placement();
            if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions == null) continue;
                for (ChunkPos candidate : positions) {
                    addAvoidanceCandidate(state, holder, placement, candidate,
                            minChunkX, minChunkZ, maxChunkX, maxChunkZ, result);
                }
                continue;
            }

            if (placement instanceof RandomSpreadStructurePlacement randomSpread) {
                int spacing = randomSpread.spacing();
                int startI = Math.floorDiv(minChunkX, spacing);
                int endI = Math.floorDiv(maxChunkX, spacing);
                int startJ = Math.floorDiv(minChunkZ, spacing);
                int endJ = Math.floorDiv(maxChunkZ, spacing);
                for (int i = startI; i <= endI; i++) {
                    for (int j = startJ; j <= endJ; j++) {
                        ChunkPos candidate = randomSpread.getPotentialStructureChunk(
                                state.getLevelSeed(), i * spacing, j * spacing);
                        addAvoidanceCandidate(state, holder, placement, candidate,
                                minChunkX, minChunkZ, maxChunkX, maxChunkZ, result);
                    }
                }
                continue;
            }

            // Custom placement types do not expose a region formula. Their
            // native predicate is still authoritative, so scan only this tile.
            for (int x = minChunkX; x <= maxChunkX; x++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    addAvoidanceCandidate(state, holder, placement, new ChunkPos(x, z),
                            minChunkX, minChunkZ, maxChunkX, maxChunkZ, result);
                }
            }
        }
        return result;
    }

    private static void addAvoidanceCandidate(ChunkGeneratorStructureState state,
                                                Holder<StructureSet> structureSet,
                                                StructurePlacement placement,
                                                ChunkPos candidate,
                                                int minChunkX,
                                                int minChunkZ,
                                                int maxChunkX,
                                                int maxChunkZ,
                                                List<AvoidanceStructureCandidate> result) {
        if (candidate.x < minChunkX || candidate.x > maxChunkX
                || candidate.z < minChunkZ || candidate.z > maxChunkZ) return;
        if (!placement.isStructureChunk(state, candidate.x, candidate.z)) return;
        result.add(new AvoidanceStructureCandidate(placement.getLocatePos(candidate), structureSet));
    }

    public record AvoidanceStructureCandidate(BlockPos pos, Holder<StructureSet> structureSet) {
    }

    /**
     * 0.9.034（R-07 计数模式）：<b>只读</b>判定 —— 这个起点如果开了群系预筛会不会被挡掉。
     *
     * <p>{@link #predictAvoidanceStructuresInRect} 目前**完全没有**群系预筛：它对
     * candidateBounds 矩形里每一个 potential structure chunk 都发一个候选，然后逐个跑
     * Minecraft 原生 STRUCTURE_STARTS 精确解析。真机实测 4374 个起点里
     * {@code emptyResults=3997}（91.4%）解析完是空的，却仍要付 71 批 / 57.8 s 的 native 解析。</p>
     *
     * <p>本方法按 {@code predictStructuresInRectUnchecked} 里 RandomSpread 分支**同一套**取样
     * 约定（{@code placement.getLocatePos(chunk)} → quart(64) → {@code getNoiseBiome}）判断该
     * 起点所在地的群系是否落在该 structure set **任一**结构的 allowed biomes 并集里。并集口径是
     * 保守的：只要 set 里有一个结构允许这个群系就放行，绝不因为「集合里有别的结构不允许」而误挡。</p>
     *
     * <p><b>本方法不参与任何决策</b>，调用方只把它拿去计数。返回 {@code null} 表示无法判定
     * （非 RandomSpread 放置 / 结构 set 无 biomes 声明 / 取样抛异常），调用方必须当作「不挡」。</p>
     */
    public static Boolean biomeWouldReject(ServerLevel level, Holder<StructureSet> structureSet, ChunkPos chunk) {
        if (level == null || structureSet == null || chunk == null) return null;
        try {
            StructurePlacement placement = structureSet.value().placement();
            // 只有随机散布口径有「locate 位置」这回事；其它放置类型一律不判定。
            if (!(placement instanceof RandomSpreadStructurePlacement)) return null;
            Set<Holder<Biome>> allowed = new HashSet<>();
            for (StructureSet.StructureSelectionEntry entry : structureSet.value().structures()) {
                // HolderSet<Biome> 不是 Collection，必须逐个迭代（与 predictStructuresInRectUnchecked 一致）。
                for (Holder<Biome> biome : entry.structure().value().biomes()) {
                    allowed.add(biome);
                }
            }
            if (allowed.isEmpty()) return null;
            BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
            RandomState randomState = level.getChunkSource().getGeneratorState().randomState();
            BlockPos locatePos = placement.getLocatePos(chunk);
            Holder<Biome> sample = biomeSource.getNoiseBiome(
                    QuartPos.fromBlock(locatePos.getX()), QuartPos.fromBlock(64),
                    QuartPos.fromBlock(locatePos.getZ()), randomState.sampler());
            return !allowed.contains(sample);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static boolean isSurfaceRoadRelevant(Holder<StructureSet> structureSet) {
        if (structureSet == null) return false;
        String id = structureSet.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("")
                .toLowerCase(Locale.ROOT);
        return !id.contains("monster_room")
                && !id.contains("nether_fossil");
    }

    private static List<StructureInfo> predictStructuresInRectUnchecked(ServerLevel level,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            boolean biomePrefilter,
            List<String> whitelist,
            List<String> blacklist,
            boolean scanGenericPlacements) {

        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomState randomState = state.randomState();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();

        Filters filters = Filters.of(whitelist, blacklist);

        List<StructureInfo> result = new ArrayList<>();

        List<Holder<StructureSet>> possibleSets = state.possibleStructureSets();
        for (Holder<StructureSet> holder : possibleSets) {
            StructureSet set = holder.value();
            StructurePlacement placement = set.placement();

            List<Holder<Structure>> matchedStructures = new ArrayList<>();
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                Holder<Structure> structureHolder = entry.structure();
                Optional<ResourceKey<Structure>> key = structureHolder.unwrapKey();
                if (key.isEmpty())
                    continue;
                ResourceLocation id = key.get().location();
                if (filters.matches(structureHolder, id)) {
                    matchedStructures.add(structureHolder);
                }
            }

            if (matchedStructures.isEmpty()) {
                if (filters.hasWhitelist())
                    continue;
                for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                    Holder<Structure> structureHolder = entry.structure();
                    Optional<ResourceKey<Structure>> key = structureHolder.unwrapKey();
                    if (key.isEmpty())
                        continue;
                    ResourceLocation id = key.get().location();
                    if (!filters.isBlacklisted(structureHolder, id)) {
                        matchedStructures.add(structureHolder);
                    }
                }
                if (matchedStructures.isEmpty())
                    continue;
            }

            String labelId = matchedStructures.stream()
                    .map(h -> h.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString)
                            .orElse("structure"))
                    .findFirst().orElse("structure");

            if (placement instanceof ConcentricRingsStructurePlacement crsp) {
                List<ChunkPos> ring = state.getRingPositionsFor(crsp);
                if (ring == null || ring.isEmpty()) {
                    continue;
                }
                for (ChunkPos cp : ring) {
                    int x = cp.x;
                    int z = cp.z;
                    if (x < minChunkX || x > maxChunkX || z < minChunkZ || z > maxChunkZ)
                        continue;
                    if (!placement.isStructureChunk(state, x, z))
                        continue;
                    BlockPos locatePos = placement.getLocatePos(cp);
                    result.add(new StructureInfo(locatePos, labelId));
                }
                continue;
            }

            Set<Holder<Biome>> allowedBiomes = null;
            if (biomePrefilter) {
                allowedBiomes = new HashSet<>();
                for (Holder<Structure> h : matchedStructures) {
                    for (Holder<Biome> b : h.value().biomes()) {
                        allowedBiomes.add(b);
                    }
                }
            }

            if (!(placement instanceof RandomSpreadStructurePlacement rssp)) {
                if (!scanGenericPlacements) continue;
                for (int x = minChunkX; x <= maxChunkX; x++) {
                    for (int z = minChunkZ; z <= maxChunkZ; z++) {
                        if (!placement.isStructureChunk(state, x, z)) continue;
                        BlockPos locatePos = placement.getLocatePos(new ChunkPos(x, z));
                        String chosenId = labelId;
                        Holder<Biome> sample = sampleCandidateBiome(
                                locatePos, biomeSource, randomState, allowedBiomes, biomePrefilter);
                        if (biomePrefilter && sample == null) continue;
                        if (sample == null) {
                            sample = sampleBiomeAt(locatePos, biomeSource, randomState, 64);
                        }
                        if (biomePrefilter && allowedBiomes != null && !allowedBiomes.contains(sample)) continue;
                        for (Holder<Structure> h : matchedStructures) {
                            if (h.value().biomes().contains(sample)) {
                                chosenId = h.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString)
                                        .orElse(labelId);
                                break;
                            }
                        }
                        result.add(new StructureInfo(locatePos, chosenId));
                    }
                }
                continue;
            }

            int spacing = rssp.spacing();
            int startI = Math.floorDiv(minChunkX, spacing);
            int endI = Math.floorDiv(maxChunkX, spacing);
            int startJ = Math.floorDiv(minChunkZ, spacing);
            int endJ = Math.floorDiv(maxChunkZ, spacing);

            for (int i = startI; i <= endI; i++) {
                for (int j = startJ; j <= endJ; j++) {
                    int baseX = i * spacing;
                    int baseZ = j * spacing;
                    ChunkPos candidate = rssp.getPotentialStructureChunk(level.getSeed(), baseX, baseZ);
                    int x = candidate.x;
                    int z = candidate.z;
                    if (x < minChunkX || x > maxChunkX || z < minChunkZ || z > maxChunkZ)
                        continue;
                    if (!placement.isStructureChunk(state, x, z))
                        continue;

                    BlockPos locatePos = placement.getLocatePos(candidate);
                    Holder<Biome> sample = sampleCandidateBiome(
                            locatePos, biomeSource, randomState, allowedBiomes, biomePrefilter);
                    if (biomePrefilter && sample == null)
                        continue;
                    if (sample == null) {
                        sample = sampleBiomeAt(locatePos, biomeSource, randomState, 64);
                    }

                    String chosenId = labelId;
                    for (Holder<Structure> h : matchedStructures) {
                        if (h.value().biomes().contains(sample)) {
                            chosenId = h.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString)
                                    .orElse(labelId);
                            break;
                        }
                    }
                    result.add(new StructureInfo(locatePos, chosenId));
                }
            }
        }

        return result;
    }

    public static List<StructureInfo> predictOverworldStructuresAroundSpawn(ServerLevel level,
            int radiusChunks,
            boolean biomePrefilter,
            List<String> whitelist,
            List<String> blacklist) {
        if (!isOverworld(level)) return List.of();
        RegistryAccess access = level.registryAccess();
        Registry<StructureSet> setRegistry = access.registryOrThrow(Registries.STRUCTURE_SET);

        BlockPos spawn = level.getSharedSpawnPos();
        int cx = spawn.getX() >> 4;
        int cz = spawn.getZ() >> 4;
        int minX = cx - radiusChunks;
        int maxX = cx + radiusChunks;
        int minZ = cz - radiusChunks;
        int maxZ = cz + radiusChunks;

        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomState randomState = state.randomState();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();

        Filters filters = Filters.of(whitelist, blacklist);

        List<StructureInfo> result = new ArrayList<>();

        for (Holder.Reference<StructureSet> holder : setRegistry.holders().toList()) {
            StructureSet set = holder.value();
            StructurePlacement placement = set.placement();
            if (!(placement instanceof RandomSpreadStructurePlacement rssp))
                continue;

            List<Holder<Structure>> matchedStructures = new ArrayList<>();
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                Holder<Structure> structureHolder = entry.structure();
                Optional<ResourceKey<Structure>> key = structureHolder.unwrapKey();
                if (key.isEmpty())
                    continue;
                ResourceLocation id = key.get().location();
                if (filters.matches(structureHolder, id)) {
                    matchedStructures.add(structureHolder);
                }
            }

            if (matchedStructures.isEmpty()) {
                if (filters.hasWhitelist())
                    continue;
                for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                    Holder<Structure> structureHolder = entry.structure();
                    Optional<ResourceKey<Structure>> key = structureHolder.unwrapKey();
                    if (key.isEmpty())
                        continue;
                    ResourceLocation id = key.get().location();
                    if (!filters.isBlacklisted(structureHolder, id)) {
                        matchedStructures.add(structureHolder);
                    }
                }
                if (matchedStructures.isEmpty())
                    continue;
            }

            Set<Holder<Biome>> allowedBiomes = null;
            if (biomePrefilter) {
                allowedBiomes = new HashSet<>();
                for (Holder<Structure> h : matchedStructures) {
                    for (Holder<Biome> b : h.value().biomes()) {
                        allowedBiomes.add(b);
                    }
                }
            }

            int spacing = rssp.spacing();
            int startI = Math.floorDiv(minX, spacing);
            int endI = Math.floorDiv(maxX, spacing);
            int startJ = Math.floorDiv(minZ, spacing);
            int endJ = Math.floorDiv(maxZ, spacing);

            String labelId = matchedStructures.stream()
                    .map(h -> h.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString)
                            .orElse("structure"))
                    .findFirst().orElse("structure");

            for (int i = startI; i <= endI; i++) {
                for (int j = startJ; j <= endJ; j++) {
                    int baseX = i * spacing;
                    int baseZ = j * spacing;
                    ChunkPos candidate = rssp.getPotentialStructureChunk(level.getSeed(), baseX, baseZ);
                    int x = candidate.x;
                    int z = candidate.z;
                    if (x < minX || x > maxX || z < minZ || z > maxZ)
                        continue;
                    if (!placement.isStructureChunk(state, x, z))
                        continue;
                    BlockPos locatePos = placement.getLocatePos(candidate);
                    Holder<Biome> sample = sampleCandidateBiome(
                            locatePos, biomeSource, randomState, allowedBiomes, biomePrefilter);
                    if (biomePrefilter && sample == null)
                        continue;
                    if (sample == null) {
                        sample = sampleBiomeAt(locatePos, biomeSource, randomState, 64);
                    }
                    String chosenId = labelId;
                    for (Holder<Structure> h : matchedStructures) {
                        if (h.value().biomes().contains(sample)) {
                            chosenId = h.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString)
                                    .orElse(labelId);
                            break;
                        }
                    }
                    result.add(new StructureInfo(locatePos, chosenId));
                }
            }
        }

        return result;
    }

    private static boolean isOverworld(ServerLevel level) {
        return isOverworldDimension(level) && ConfigService.get().structurePredictionEnabled();
    }

    private static Holder<Biome> sampleCandidateBiome(
            BlockPos locatePos,
            BiomeSource biomeSource,
            RandomState randomState,
            Set<Holder<Biome>> allowedBiomes,
            boolean biomePrefilter) {
        if (!biomePrefilter || allowedBiomes == null || allowedBiomes.isEmpty()) {
            return sampleBiomeAt(locatePos, biomeSource, randomState, 64);
        }
        for (int height : BIOME_PREFILTER_SAMPLE_HEIGHTS) {
            Holder<Biome> sample = sampleBiomeAt(locatePos, biomeSource, randomState, height);
            if (allowedBiomes.contains(sample)) return sample;
        }
        return null;
    }

    private static Holder<Biome> sampleBiomeAt(
            BlockPos locatePos,
            BiomeSource biomeSource,
            RandomState randomState,
            int height) {
        int qx = QuartPos.fromBlock(locatePos.getX());
        int qy = QuartPos.fromBlock(height);
        int qz = QuartPos.fromBlock(locatePos.getZ());
        return biomeSource.getNoiseBiome(qx, qy, qz, randomState.sampler());
    }

    private static boolean isOverworldDimension(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }

    private static final class Filters {
        private final List<String> whitelist;
        private final List<String> blacklist;

        private Filters(List<String> whitelist, List<String> blacklist) {
            this.whitelist = normalize(whitelist);
            this.blacklist = normalize(blacklist);
        }

        static Filters of(List<String> whitelist, List<String> blacklist) {
            return new Filters(whitelist, blacklist);
        }

        boolean hasWhitelist() {
            return !whitelist.isEmpty();
        }

        boolean matches(Holder<Structure> holder, ResourceLocation id) {
            boolean whiteOk = whitelist.isEmpty() || whitelist.stream().anyMatch(p -> matchesPattern(holder, id, p));
            boolean blackHit = blacklist.stream().anyMatch(p -> matchesPattern(holder, id, p));
            return whiteOk && !blackHit;
        }

        boolean isBlacklisted(Holder<Structure> holder, ResourceLocation id) {
            return blacklist.stream().anyMatch(p -> matchesPattern(holder, id, p));
        }

        private boolean matchesPattern(Holder<Structure> holder, ResourceLocation id, String pattern) {
            if (pattern == null || pattern.isEmpty())
                return false;
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            String idStr = id.toString().toLowerCase(Locale.ROOT);
            if (p.startsWith("#")) {
                String raw = p.substring(1);
                ResourceLocation tagId = ResourceLocation.tryParse(raw);
                if (tagId == null)
                    return false;
                TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, tagId);
                return holder.is(tag);
            }
            if (p.endsWith("/*")) {
                String base = p.substring(0, p.length() - 2);
                if (idStr.startsWith(base + "/"))
                    return true;
                if (idStr.startsWith(base + "_"))
                    return true;
                if (idStr.startsWith(base + "-"))
                    return true;
                if (idStr.startsWith(base + "."))
                    return true;
                return false;
            }
            if (p.endsWith(":*")) {
                String ns = p.substring(0, p.length() - 2);
                int idx = ns.indexOf(':');
                if (idx > 0)
                    ns = ns.substring(0, idx);
                return id.getNamespace().equalsIgnoreCase(ns);
            }
            return idStr.equals(p);
        }

        private static List<String> normalize(List<String> src) {
            List<String> out = new ArrayList<>();
            if (src == null)
                return out;
            for (String s : src) {
                if (s == null)
                    continue;
                String v = s.trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty())
                    out.add(v);
            }
            return out;
        }
    }
}
