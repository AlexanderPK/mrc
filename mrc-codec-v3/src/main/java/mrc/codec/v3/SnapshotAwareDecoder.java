package mrc.codec.v3;

import mrc.codec.MrcDecoder;
import mrc.codec.MrcFormatException;
import mrc.snapshot.MrcSnapshotException;
import mrc.snapshotdb.SnapshotStore;

import java.io.*;
import java.nio.file.*;

/**
 * Decodes MRC v0x03 compressed data produced by {@link SnapshotAwareEncoder}.
 *
 * <p>In <em>connected</em> mode the snapshot is loaded from the provided
 * {@link SnapshotStore} using the ID embedded in the header.  In
 * <em>standalone</em> mode the snapshot bytes are read directly from the
 * compressed stream — no store access is required.
 */
public class SnapshotAwareDecoder {

    /**
     * Decode connected-mode data.  The store must contain the snapshot
     * referenced in the header.
     */
    public byte[] decodeConnected(byte[] compressed, SnapshotStore store)
            throws IOException, MrcSnapshotException, MrcFormatException {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        V3Header header = V3Header.read(bais);
        if (header.mode() != V3Header.MODE_CONNECTED) {
            throw new MrcV3FormatException("Expected connected mode (0x01), got 0x"
                    + String.format("%02x", header.mode() & 0xFF));
        }
        // Verify snapshot exists
        store.load(header.snapshotId());

        byte[] body = bais.readNBytes(header.bodyLength());
        return new MrcDecoder().decode(body);
    }

    /**
     * Decode standalone-mode data.  No store is required — the snapshot is
     * embedded in the stream.
     */
    public byte[] decodeStandalone(byte[] compressed)
            throws IOException, MrcSnapshotException, MrcFormatException {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        V3Header header = V3Header.read(bais);
        if (header.mode() != V3Header.MODE_STANDALONE) {
            throw new MrcV3FormatException("Expected standalone mode (0x00), got 0x"
                    + String.format("%02x", header.mode() & 0xFF));
        }

        // Read embedded snapshot length + bytes (not used for decoding body, but validate)
        byte[] snapLenBytes = bais.readNBytes(4);
        if (snapLenBytes.length < 4) throw new MrcV3FormatException("Truncated standalone snapshot length");
        int snapLen = ((snapLenBytes[0] & 0xFF) << 24) | ((snapLenBytes[1] & 0xFF) << 16)
                | ((snapLenBytes[2] & 0xFF) << 8) | (snapLenBytes[3] & 0xFF);
        bais.skipNBytes(snapLen); // skip embedded snapshot data

        byte[] body = bais.readNBytes(header.bodyLength());
        return new MrcDecoder().decode(body);
    }

    /**
     * Extract the snapshot ID from compressed data without fully decoding it.
     */
    public String readSnapshotId(byte[] compressed) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        return V3Header.read(bais).snapshotId();
    }

    /**
     * Extract the embedded snapshot bytes from a standalone-mode stream
     * and write them to a temp file, returning its path.
     */
    public Path extractEmbeddedSnapshot(byte[] compressed) throws IOException, MrcSnapshotException {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        V3Header header = V3Header.read(bais);
        if (header.mode() != V3Header.MODE_STANDALONE) {
            throw new MrcV3FormatException("Not a standalone stream");
        }
        byte[] snapLenBytes = bais.readNBytes(4);
        int snapLen = ((snapLenBytes[0] & 0xFF) << 24) | ((snapLenBytes[1] & 0xFF) << 16)
                | ((snapLenBytes[2] & 0xFF) << 8) | (snapLenBytes[3] & 0xFF);
        byte[] snapBytes = bais.readNBytes(snapLen);
        Path tmp = Files.createTempFile("mrc3-snap-", ".snap");
        Files.write(tmp, snapBytes);
        return tmp;
    }
}
