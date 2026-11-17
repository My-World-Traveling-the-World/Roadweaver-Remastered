/* 文件职责：验证流式解析不能漏字段、截断64位指纹或吞掉多余文档。 */
package net.shiroha233.roadweaver.persistence.chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class RoadShardDocumentReaderTest {
    @TempDir Path directory;
    private static final String VALID="{\"schema\":\"roadweaver.roads.chunk\",\"version\":2,\"chunkX\":-1,\"chunkZ\":2,\"roads\":[9223372036854775807,-9223372036854775808],\"generation\":7}";
    @Test void exactLongReferencesOrderAndUnknownExtensionsArePreserved() throws Exception {
        assertTrue(check(VALID));
        assertTrue(check(VALID.replace("\"generation\":7","\"extension\":{\"array\":[1,null]},\"generation\":99")));
        assertFalse(check(VALID.replace("9223372036854775807,-9223372036854775808","-9223372036854775808,9223372036854775807")));
        assertFalse(check(VALID.replace("9223372036854775807","9223372036854775806")));
    }
    @Test void requiredFieldsTypesDuplicatesLengthsAndTrailingContentAreRejected() throws Exception {
        String[] broken={
            VALID.replace("\"version\":2,",""),
            VALID.replace("\"version\":2","\"version\":3"),
            VALID.replace("\"version\":2","\"version\":2,\"version\":2"),
            VALID.replace("\"chunkX\":-1","\"chunkX\":0"),
            VALID.replace("\"chunkZ\":2","\"chunkZ\":3"),
            VALID.replace("roadweaver.roads.chunk","other"),
            VALID.replace("\"schema\":\"roadweaver.roads.chunk\"","\"schema\":null"),
            VALID.replace("\"roads\":[9223372036854775807,-9223372036854775808]","\"roads\":null"),
            VALID.replace("9223372036854775807,-9223372036854775808","9223372036854775807"),
            VALID.replace("9223372036854775807,-9223372036854775808","9223372036854775807,-9223372036854775808,0"),
            VALID.replace("-9223372036854775808","null"),
            VALID.replace("\"version\":2","\"version\":2.5"),
            VALID+"{}",
            VALID.substring(0,VALID.length()-1)};
        assertEquals(14,broken.length);
        for(int i=0;i<broken.length;i++)assertFalse(accepted(broken[i]),"非法分片必须拒绝，夹具"+i);
    }
    private boolean accepted(String json) throws Exception {
        try{return check(json);}catch(com.google.gson.stream.MalformedJsonException | java.io.EOFException | NumberFormatException | IllegalStateException malformed){return false;}
    }
    private boolean check(String json) throws Exception {
        Path file=directory.resolve("-1_2.json");Files.writeString(file,json);
        return RoadShardDocumentReader.matches(file,new RoadChunkKey(-1,2),new long[]{Long.MAX_VALUE,Long.MIN_VALUE});
    }
}
