package net.shiroha233.roadweaver.core.model;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.RandomAccess;
import net.minecraft.core.BlockPos;

/**
 * 结构位置集合，兼容旧版（仅位置列表）和新版（含类型信息）格式
 */
public record StructureLocationData(List<BlockPos> structureLocations, List<StructureInfo> structureInfos) {

    public StructureLocationData(List<BlockPos> structureLocations, List<StructureInfo> structureInfos) {
        this.structureLocations = copyUnlessFrozen(structureLocations);
        this.structureInfos = copyUnlessFrozen(structureInfos);
    }

    /**
     * 只读共享实例：列表以不可变包装存储，任何写操作都会抛
     * {@link UnsupportedOperationException}。仅供只读快照缓存使用，
     * 避免缓存对象被外部改动。常规可变实例仍走普通构造器。
     */
    public static StructureLocationData frozen(List<BlockPos> structureLocations, List<StructureInfo> structureInfos) {
        return new StructureLocationData(
                new FrozenList<>(structureLocations),
                new FrozenList<>(structureInfos));
    }

    private static <T> List<T> copyUnlessFrozen(List<T> source) {
        if (source instanceof FrozenList<?>) return source;
        if (source == null) return new ArrayList<>();
        return new ArrayList<>(source);
    }

    private static final class FrozenList<E> extends AbstractList<E> implements RandomAccess {
        private final List<E> delegate;

        private FrozenList(List<E> source) {
            this.delegate = new ArrayList<>(source == null ? List.of() : source);
        }

        @Override
        public E get(int index) {
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }

    public StructureLocationData(List<BlockPos> structureLocations) {
        this(structureLocations, new ArrayList<>());
    }

    public void addStructure(BlockPos pos) {
        structureLocations.add(pos);
    }

    public void addStructureInfo(StructureInfo info) {
        structureInfos.add(info);
        if (!structureLocations.contains(info.pos())) {
            structureLocations.add(info.pos());
        }
    }

    public static final Codec<StructureLocationData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.listOf().optionalFieldOf("structure_locations", new ArrayList<>()).forGetter(StructureLocationData::structureLocations),
                    StructureInfo.CODEC.listOf().optionalFieldOf("structure_infos", new ArrayList<>()).forGetter(StructureLocationData::structureInfos)
            ).apply(instance, StructureLocationData::new)
    );
}
