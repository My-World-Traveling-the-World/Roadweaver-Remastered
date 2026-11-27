package net.shiroha233.roadweaver.persistence.sharded;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 用相同区块和坐标验证两个世界不串队；写入失败必须还能取回原队列。 */
final class SignPendingQueueTest {
    @Test void samePositionInTwoWorldsSurvivesFailedFlushWithoutCrossing() {
        var first = new SignTextShardStorage.PendingQueue();
        var second = new SignTextShardStorage.PendingQueue();
        var pos = new BlockPos(1, 65, 1);
        var a = new SignTextShardStorage.PendingSignWrite(pos, 0, "甲", 0, 1L);
        var b = new SignTextShardStorage.PendingSignWrite(pos, 0, "乙", 0, 2L);
        first.put(a, false);
        second.put(b, false);
        first.put(b, true);
        assertThrows(IllegalStateException.class, () -> first.flush(writes -> {
            assertEquals(List.of(a), writes);
            throw new IllegalStateException("模拟分片写入失败");
        }));
        second.flush(writes -> assertEquals(List.of(b), writes));
        assertFalse(second.has(0));
        assertEquals(List.of(a), first.take(0, 1));
        assertFalse(first.has(0));
    }
}
