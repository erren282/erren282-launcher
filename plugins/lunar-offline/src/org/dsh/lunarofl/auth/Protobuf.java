package org.dsh.lunarofl.auth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// 只够 gameipc 协议用的 protobuf 线格式编解码（纯 JDK 实现）。
///
/// <p>Lunar 的 gameipc WebSocket 协议里所有消息都是 protobuf，但结构都很小
/// （信封 4~8 个字段、auth 请求就一个 fixed64 对），引 protobuf-java 依赖反而累赘。
/// 这里只实现用到的三种线类型：varint（含 bool / int32）、length-delimited
/// （string / bytes / 嵌套 message）、fixed64。</p>
public final class Protobuf {

    private Protobuf() {
    }

    // ---------- 写 ----------

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(int fieldNumber, long value) {
            writeTag(fieldNumber, 0);
            long v = value;
            while (true) {
                if ((v & ~0x7FL) == 0) {
                    out.write((int) v);
                    return this;
                }
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
        }

        public Writer bool(int fieldNumber, boolean value) {
            return varint(fieldNumber, value ? 1 : 0);
        }

        public Writer string(int fieldNumber, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return bytes(fieldNumber, bytes);
        }

        public Writer bytes(int fieldNumber, byte[] value) {
            writeTag(fieldNumber, 2);
            int length = value.length;
            while (true) {
                if ((length & ~0x7F) == 0) {
                    out.write(length);
                    break;
                }
                out.write((length & 0x7F) | 0x80);
                length >>>= 7;
            }
            out.write(value, 0, value.length);
            return this;
        }

        public Writer message(int fieldNumber, byte[] nested) {
            return bytes(fieldNumber, nested);
        }

        public Writer fixed64(int fieldNumber, long value) {
            writeTag(fieldNumber, 1);
            for (int i = 0; i < 8; i++)
                out.write((int) ((value >>> (8 * i)) & 0xFF));
            return this;
        }

        private void writeTag(int fieldNumber, int wireType) {
            long tag = ((long) fieldNumber << 3) | wireType;
            while (true) {
                if ((tag & ~0x7FL) == 0) {
                    out.write((int) tag);
                    return;
                }
                out.write((int) ((tag & 0x7F) | 0x80));
                tag >>>= 7;
            }
        }

        public byte[] build() {
            return out.toByteArray();
        }
    }

    // ---------- 读 ----------

    public static final class Reader {
        private final byte[] data;
        private int pos;
        private int end;

        public Reader(byte[] data) {
            this(data, 0, data.length);
        }

        Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.end = offset + length;
        }

        public boolean hasNext() {
            return pos < end;
        }

        /// 下一个字段的编号；不移动游标（先看编号，再调对应 readXxx）。
        public int peekField() {
            long tag = readVarintRaw();
            pos -= varintSize(tag);
            return (int) (tag >>> 3);
        }

        /// 消费掉字段 tag（调用约定：peekField() 把游标退回 tag 处，readXxx 从 tag 开始读）。
        private void consumeTag() {
            readVarintRaw();
        }

        public long readVarint() {
            consumeTag();
            return readVarintRaw();
        }

        public boolean readBool() {
            consumeTag();
            return readVarintRaw() != 0;
        }

        public long readFixed64() {
            consumeTag();
            if (pos + 8 > end)
                throw new IllegalArgumentException("protobuf fixed64 越界");
            long value = 0;
            for (int i = 0; i < 8; i++)
                value |= (data[pos + i] & 0xFFL) << (8 * i);
            pos += 8;
            return value;
        }

        public byte[] readBytes() {
            consumeTag();
            long length = readVarintRaw();
            if (length < 0 || pos + length > end)
                throw new IllegalArgumentException("protobuf 长度越界");
            byte[] result = new byte[(int) length];
            System.arraycopy(data, pos, result, 0, (int) length);
            pos += (int) length;
            return result;
        }

        public String readString() {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        /// length-delimited 字段的子 Reader。
        public Reader readMessage() {
            byte[] nested = readBytes();
            return new Reader(nested, 0, nested.length);
        }

        /// 跳过当前字段（未知字段时用）。
        public void skip() {
            long tag = readVarintRaw();
            int wireType = (int) (tag & 7);
            switch (wireType) {
                case 0 -> readVarintRaw();
                case 1 -> {
                    if (pos + 8 > end)
                        throw new IllegalArgumentException("protobuf fixed64 越界");
                    pos += 8;
                }
                case 2 -> {
                    // readBytes() 按约定会先消费 tag，而 skip 已经消费过 tag 了，
                    // 这里必须自己读长度，否则会错位解析（实际踩过的坑）
                    long length = readVarintRaw();
                    if (length < 0 || pos + length > end)
                        throw new IllegalArgumentException("protobuf 长度越界");
                    pos += (int) length;
                }
                case 5 -> {
                    if (pos + 4 > end)
                        throw new IllegalArgumentException("protobuf fixed32 越界");
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("protobuf 不支持的线类型 " + wireType);
            }
        }

        private long readVarintRaw() {
            long value = 0;
            int shift = 0;
            while (pos < end) {
                byte b = data[pos++];
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0)
                    return value;
                shift += 7;
                if (shift >= 64)
                    break;
            }
            throw new IllegalArgumentException("protobuf varint 未正常终止");
        }

        private int varintSize(long value) {
            int size = 1;
            while ((value & ~0x7FL) != 0) {
                value >>>= 7;
                size++;
            }
            return size;
        }
    }

    // ---------- 便捷解码 ----------

    /// 从消息里取指定编号的 length-delimited 字段（第一个出现的），找不到返回 null。
    public static byte[] getFieldBytes(byte[] message, int fieldNumber) {
        Reader reader = new Reader(message);
        while (reader.hasNext()) {
            if (reader.peekField() == fieldNumber)
                return reader.readBytes();
            reader.skip();
        }
        return null;
    }

    /// 从消息里取指定编号的字符串字段，找不到返回 null。
    public static String getFieldString(byte[] message, int fieldNumber) {
        byte[] bytes = getFieldBytes(message, fieldNumber);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /// 把 UUID 拆成 protobuf Uuid（lunarclient.common.v1）的 high64/low64。
    public static byte[] uuidToMessage(java.util.UUID uuid) {
        return new Writer()
                .fixed64(1, uuid.getMostSignificantBits())
                .fixed64(2, uuid.getLeastSignificantBits())
                .build();
    }

    /// protobuf Uuid（high64/low64）还原成 UUID。
    public static java.util.UUID messageToUuid(byte[] message) {
        Reader reader = new Reader(message);
        long high = 0;
        long low = 0;
        while (reader.hasNext()) {
            switch (reader.peekField()) {
                case 1 -> high = reader.readFixed64();
                case 2 -> low = reader.readFixed64();
                default -> reader.skip();
            }
        }
        return new java.util.UUID(high, low);
    }

    /// 列出消息里的所有字段编号（调试用）。
    public static List<Integer> fields(byte[] message) {
        List<Integer> result = new ArrayList<>();
        Reader reader = new Reader(message);
        while (reader.hasNext()) {
            result.add(reader.peekField());
            reader.skip();
        }
        return result;
    }
}
