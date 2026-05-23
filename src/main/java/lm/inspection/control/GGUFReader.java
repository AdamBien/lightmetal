package lm.inspection.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import lm.inspection.entity.GGUFMetadata;

public final class GGUFReader {

    private static final int MAGIC = 0x46554747; // "GGUF" little-endian
    private static final long MAX_HEADER_MAP = 256L * 1024 * 1024;

    private static final int U8 = 0, I8 = 1, U16 = 2, I16 = 3;
    private static final int U32 = 4, I32 = 5, F32 = 6, BOOL = 7;
    private static final int STRING = 8, ARRAY = 9;
    private static final int U64 = 10, I64 = 11, F64 = 12;

    private GGUFReader() {}

    public static GGUFMetadata read(Path file) {
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            var size = Math.min(ch.size(), MAX_HEADER_MAP);
            var buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN);
            return parse(buf);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read GGUF: " + file, e);
        }
    }

    public static GGUFMetadata parse(ByteBuffer buf) {
        var magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("not a GGUF file (magic=0x" + Integer.toHexString(magic) + ")");
        }
        var version = buf.getInt();
        if (version < 2 || version > 3) {
            throw new IllegalArgumentException("unsupported GGUF version: " + version + " (only v2 and v3 supported)");
        }
        var tensorCount = buf.getLong();
        var kvCount = buf.getLong();

        var kvs = new LinkedHashMap<String, Object>();
        for (var i = 0L; i < kvCount; i++) {
            var key = readString(buf);
            var type = buf.getInt();
            kvs.put(key, readValue(buf, type));
        }
        return new GGUFMetadata(version, tensorCount, Map.copyOf(kvs));
    }

    private static String readString(ByteBuffer buf) {
        var len = buf.getLong();
        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid string length: " + len);
        }
        var bytes = new byte[(int) len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Object readValue(ByteBuffer buf, int type) {
        return switch (type) {
            case U8 -> Byte.toUnsignedInt(buf.get());
            case I8 -> (int) buf.get();
            case U16 -> Short.toUnsignedInt(buf.getShort());
            case I16 -> (int) buf.getShort();
            case U32 -> Integer.toUnsignedLong(buf.getInt());
            case I32 -> buf.getInt();
            case F32 -> buf.getFloat();
            case BOOL -> buf.get() != 0;
            case STRING -> readString(buf);
            case U64, I64 -> buf.getLong();
            case F64 -> buf.getDouble();
            case ARRAY -> readArray(buf);
            default -> throw new IllegalArgumentException("unknown GGUF value type: " + type);
        };
    }

    private static Object[] readArray(ByteBuffer buf) {
        var elemType = buf.getInt();
        var length = buf.getLong();
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid array length: " + length);
        }
        var out = new Object[(int) length];
        for (var i = 0; i < out.length; i++) {
            out[i] = readValue(buf, elemType);
        }
        return out;
    }
}
