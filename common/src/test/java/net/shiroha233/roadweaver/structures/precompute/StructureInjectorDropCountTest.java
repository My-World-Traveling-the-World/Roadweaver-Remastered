package net.shiroha233.roadweaver.structures.precompute;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructureInjectorDropCountTest {
    private static final ChunkPos CHUNK = new ChunkPos(9, -11);

    @Test
    void 空批不读注册表也不标记区块() {
        long before = StructureInjector.droppedPendingCount();
        // 不可把暂时没有条目的区块标为注入完成，之后仍可能登记结构。
        StructureInjector.injectBatch(List.<String>of(), item -> fail("不能查注册表"),
                (item, value) -> fail("不能写结构"), item -> fail("不能丢弃"), (item, error) -> fail(error),
                () -> fail("不能标记空批"), "structure", CHUNK);
        assertEquals(before, StructureInjector.droppedPendingCount());
    }

    @Test
    void 注册表缺失和真实拒绝在清批后都算丢弃() {
        long before = StructureInjector.droppedPendingCount();
        var registry = Map.of("present", 1, "rejected", 2, "broken", 3);
        var attempted = new ArrayList<String>();
        var missing = new ArrayList<String>();
        var failed = new ArrayList<String>();
        var marks = new AtomicInteger();
        StructureInjector.injectBatch(List.of("unknown", "present", "rejected", "broken"), registry::get,
                (item, structure) -> {
                    attempted.add(item);
                    if (structure == 3) throw new IllegalArgumentException("模拟结构损坏");
                    return structure == 1;
                }, missing::add, (item, error) -> failed.add(item), () -> {
                    assertEquals(before, StructureInjector.droppedPendingCount(), "先标记再结算");
                    marks.incrementAndGet();
                }, "structure", CHUNK);
        assertEquals(List.of("present", "rejected", "broken"), attempted, "未知 ID 不能写世界");
        assertEquals(List.of("unknown"), missing);
        assertEquals(List.of("broken"), failed);
        assertEquals(1, marks.get());
        assertEquals(before + 3, StructureInjector.droppedPendingCount(), "查不到 ID 也被整批清掉");
    }

    @Test
    void 标记成功后丢弃计数必须按实际条数累加() {
        long before = StructureInjector.droppedPendingCount();
        var marks = new AtomicInteger();
        StructureInjector.finishInjection(() -> {
            assertEquals(before, StructureInjector.droppedPendingCount(), "不能先计数后标记");
            marks.incrementAndGet();
        }, "structure", CHUNK, 1, 2);
        assertEquals(before + 2, StructureInjector.droppedPendingCount(), "M11：读到的真实增量应为 2");

        StructureInjector.finishInjection(marks::incrementAndGet, "roadside village", CHUNK, 0, 3);
        assertEquals(before + 5, StructureInjector.droppedPendingCount());
        assertEquals(2, marks.get());
    }

    @Test
    void 标记失败不结算且无丢弃时标记仍执行() {
        long before = StructureInjector.droppedPendingCount();
        assertThrows(IllegalStateException.class, () -> StructureInjector.finishInjection(() -> {
            throw new IllegalStateException("mark failed");
        }, "structure", CHUNK, 0, 2));
        assertEquals(before, StructureInjector.droppedPendingCount());
        var marks = new AtomicInteger();
        StructureInjector.finishInjection(marks::incrementAndGet, "structure", CHUNK, 1, 0);
        assertEquals(1, marks.get());
        assertEquals(before, StructureInjector.droppedPendingCount());
    }

}
