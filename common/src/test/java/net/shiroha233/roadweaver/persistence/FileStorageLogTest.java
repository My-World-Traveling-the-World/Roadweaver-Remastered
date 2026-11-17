/* 文件职责：真实文件隔离的成功和失败必须给出不同中文含义，并保留原异常。 */
package net.shiroha233.roadweaver.persistence;

import com.sun.nio.file.ExtendedOpenOption;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import static org.junit.jupiter.api.Assertions.*;

class FileStorageLogTest {
    private record Event(String format, List<Object> args) { }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quarantineSuccessAndFailureHaveDifferentMeaning(boolean locked, @TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("broken.txt"), "keep these bytes");
        var events = new ArrayList<Event>();
        Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    assertEquals("warn", method.getName());
                    Object[] values = args.length == 2 && args[1] instanceof Object[] array
                            ? array : Arrays.copyOfRange(args, 1, args.length);
                    events.add(new Event((String) args[0], List.of(values)));
                    return null;
                });
        // Windows真实共享锁阻止移动，避免用文案自比较冒充实际故障。
        try (var held = locked ? FileChannel.open(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE) : null) {
            FileStorageIO.quarantineCorrupt(file, logger, "NBT校验失败");
            assertEquals(1, events.size());
            Event event = events.getFirst();
            assertEquals(locked, Files.exists(file));
            if (locked) {
                assertTrue(event.format().contains("隔离损坏文件失败"));
                assertInstanceOf(java.io.IOException.class, event.args().getLast());
                assertEquals("keep these bytes", Files.readString(file));
            } else {
                assertTrue(event.format().contains("已隔离损坏文件"));
                assertFalse(event.format().contains("失败"));
                Path target = assertInstanceOf(Path.class, event.args().getLast());
                assertEquals("keep these bytes", Files.readString(target));
                assertTrue(target.getFileName().toString().startsWith("broken.txt.corrupt."));
            }
        }
    }
}
