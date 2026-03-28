package mrc.codec.v3;

import java.io.*;

/**
 * Fixed-length header for the MRC v0x03 compressed format.
 *
 * <pre>
 * Offset  Size  Field
 * 0       4     magic = 0x4D 0x52 0x43 0x33 ("MRC3")
 * 4       1     mode  (0x00=standalone, 0x01=connected)
 * 5       32    snapshot_id (raw SHA-256 bytes, 32 bytes = 256 bits)
 * 37      4     body_length (big-endian int)
 * 41      ...   encoded body
 * [if standalone: 4-byte snapshot_len + snapshot bytes follow body_length field]
 * </pre>
 */
public record V3Header(byte mode, byte[] snapshotIdBytes, int bodyLength) {

    public static final byte[] MAGIC = {0x4D, 0x52, 0x43, 0x33};
    public static final int FIXED_SIZE = 41; // magic(4) + mode(1) + id(32) + body_len(4)
    public static final byte MODE_STANDALONE = 0x00;
    public static final byte MODE_CONNECTED  = 0x01;

    /**
     * Write this header to the stream.
     */
    public void write(OutputStream out) throws IOException {
        out.write(MAGIC);
        out.write(mode & 0xFF);
        out.write(snapshotIdBytes); // 32 bytes
        writeInt(out, bodyLength);
    }

    /**
     * Read a header from the stream.
     *
     * @throws MrcV3FormatException if magic bytes are invalid or data is truncated
     */
    public static V3Header read(InputStream in) throws IOException {
        byte[] magic = in.readNBytes(4);
        if (magic.length < 4 ||
            magic[0] != MAGIC[0] || magic[1] != MAGIC[1] ||
            magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new MrcV3FormatException("Invalid MRC3 magic bytes");
        }
        int mode = in.read();
        if (mode == -1) throw new MrcV3FormatException("Truncated header: missing mode byte");

        byte[] id = in.readNBytes(32);
        if (id.length < 32) throw new MrcV3FormatException("Truncated header: missing snapshot_id");

        byte[] lenBytes = in.readNBytes(4);
        if (lenBytes.length < 4) throw new MrcV3FormatException("Truncated header: missing body_length");
        int bodyLen = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16)
                | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);

        return new V3Header((byte) mode, id, bodyLen);
    }

    /** Hex-encode the snapshot ID bytes to a 64-char string. */
    public String snapshotId() {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : snapshotIdBytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>>  8) & 0xFF);
        out.write(value & 0xFF);
    }
}
