package mrc.snapshot;

import java.io.*;

/**
 * Snapshot version identifier for format compatibility checking.
 */
public record SnapshotVersion(int major, int minor) {
    public static final SnapshotVersion CURRENT = new SnapshotVersion(2, 0);

    /**
     * Check if this version is compatible with another.
     * Compatible if major versions match (minor is backward-compatible).
     */
    public boolean isCompatible(SnapshotVersion other) {
        return this.major == other.major;
    }

    /**
     * Read version from bytes (2 bytes, big-endian).
     * Format: 1 byte major, 1 byte minor.
     */
    public static SnapshotVersion fromBytes(byte[] header, int offset) {
        int major = header[offset] & 0xFF;
        int minor = header[offset + 1] & 0xFF;
        return new SnapshotVersion(major, minor);
    }

    /**
     * Write version to bytes (2 bytes, big-endian).
     */
    public byte[] toBytes() {
        byte[] data = new byte[2];
        data[0] = (byte) major;
        data[1] = (byte) minor;
        return data;
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
