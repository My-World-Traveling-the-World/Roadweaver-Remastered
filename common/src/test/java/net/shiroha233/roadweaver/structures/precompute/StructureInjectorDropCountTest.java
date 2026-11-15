package net.shiroha233.roadweaver.structures.precompute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructureInjectorDropCountTest {
    private static final ChunkPos CHUNK = new ChunkPos(9, -11);

    @Test
    void 两条注入路径都接入结算() throws IOException {
        String text = source();
        // 校验实际接线，避免共享结算方法仍在却有一条生产路径不调用。
        assertTrue(text.contains("finishInjection(() -> PendingStructureStorage.markAsInjected(level, chunkPos)"));
        assertTrue(text.contains("finishInjection(() -> PendingRoadsideVillageStorage.markAsInjected(level, chunkPos)"));
    }

    @Test
    void 跳过和异常分支仍记录本次丢弃() throws IOException {
        // 探测各路径已有的计数接线；生产注册表/Jigsaw 的丢弃分支需要真机夹具。
        String text = source();
        assertEquals(5, text.split("droppedCount\\+\\+;", -1).length - 1);
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

    private static String source() throws IOException {
        Path source = Path.of("src", "main", "java", "net", "shiroha233", "roadweaver",
                "structures", "precompute", "StructureInjector.java");
        if (!Files.isRegularFile(source)) source = Path.of("common").resolve(source);
        // 找不到源码必须失败，不能把未执行的接线守卫算成通过。
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
