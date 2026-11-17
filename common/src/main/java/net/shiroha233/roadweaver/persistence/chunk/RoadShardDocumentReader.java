/* 文件职责：流式核对分片字段和有序道路引用，避免为每份小文件创建JSON树/装箱引用列表。 */
package net.shiroha233.roadweaver.persistence.chunk;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 未知扩展字段跳过；必填字段、重复字段、引用顺序和完整文档都必须校验。 */
final class RoadShardDocumentReader {
    private RoadShardDocumentReader() {}
    static boolean matches(Path file, RoadChunkKey key, long[] expected) throws IOException {
        try (var source=Files.newBufferedReader(file,StandardCharsets.UTF_8);var reader=new JsonReader(source)) {
            if(reader.peek()!=JsonToken.BEGIN_OBJECT)return false;
            int fields=0;boolean valid=expected!=null;
            reader.beginObject();
            while(reader.hasNext()){
                String name=reader.nextName();
                int bit=switch(name){case "schema" -> 1;case "version" -> 2;case "chunkX" -> 4;case "chunkZ" -> 8;case "roads" -> 16;default -> 0;};
                if(bit!=0 && (fields&bit)!=0)valid=false;
                fields|=bit;
                switch(name){
                    case "schema" -> {
                        if(reader.peek()==JsonToken.STRING)valid&="roadweaver.roads.chunk".equals(reader.nextString());
                        else {valid=false;reader.skipValue();}
                    }
                    case "version" -> valid&=integer(reader)==RoadChunkStore.SCHEMA_VERSION;
                    case "chunkX" -> valid&=integer(reader)==key.x();
                    case "chunkZ" -> valid&=integer(reader)==key.z();
                    case "roads" -> {
                        if(reader.peek()!=JsonToken.BEGIN_ARRAY)return false;
                        int count=0;reader.beginArray();
                        while(reader.hasNext()){
                            if(reader.peek()!=JsonToken.NUMBER){valid=false;reader.skipValue();}
                            else {
                                long value=Long.parseLong(reader.nextString());
                                if(expected==null || count>=expected.length || expected[count]!=value)valid=false;
                            }
                            count++;
                        }
                        reader.endArray();valid&=expected!=null && count==expected.length;
                    }
                    default -> reader.skipValue(); // generation沿用旧验证口径，不因其他区块更新而要求全分片同代。
                }
            }
            reader.endObject();
            return valid && fields==31 && reader.peek()==JsonToken.END_DOCUMENT;
        }
    }
    private static long integer(JsonReader reader) throws IOException {
        if(reader.peek()!=JsonToken.NUMBER){reader.skipValue();return Long.MIN_VALUE;}
        // 不用nextInt/nextLong的double回退，避免截断小数或大指纹精度丢失后误判相等。
        return Long.parseLong(reader.nextString());
    }
}
