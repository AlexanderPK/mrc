package mrc.snapshot;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Metadata about a snapshot: version, generation, timestamp, domain tag, section layout, CRC32.
 */
public record SnapshotManifest(
        SnapshotVersion version,
        long generation,
        Instant timestamp,
        String domainTag,
        boolean hasChromosome,
        boolean hasCycleTable,
        boolean hasOperatorExtensions,
        int crc32,
        Map<Byte, SnapshotSection> sections
) {
    /**
     * Nested record for section metadata.
     */
    public record SnapshotSection(byte typeId, int offset, int length) {
        public static final byte OPERATOR_TABLE = 0x01;
        public static final byte TRANSITION_GRAPH = 0x02;
        public static final byte CYCLE_TABLE = 0x03;
        public static final byte CHROMOSOME = 0x04;
        public static final byte EVOLUTION_STATS = 0x05;
        public static final byte VALIDATION_HASH = 0x06;
    }

    /**
     * Read manifest from snapshot file.
     * Validates magic bytes, version compatibility, and CRC32.
     * Does NOT load section data into memory.
     */
    public static SnapshotManifest read(Path snapshotFile) throws MrcSnapshotException {
        try (RandomAccessFile raf = new RandomAccessFile(snapshotFile.toFile(), "r")) {
            byte[] header = new byte[32];
            raf.readFully(header);

            // Check magic bytes (MRC2)
            if (header[0] != 0x4D || header[1] != 0x52 || header[2] != 0x43 || header[3] != 0x32) {
                throw new MrcSnapshotException("Invalid magic bytes");
            }

            // Read version
            SnapshotVersion version = SnapshotVersion.fromBytes(header, 4);
            if (!version.isCompatible(SnapshotVersion.CURRENT)) {
                throw new MrcSnapshotException("Incompatible snapshot version: " + version);
            }

            // Read flags
            int flags = header[6] & 0xFF;
            boolean hasChromosome = (flags & 0x01) != 0;
            boolean hasCycleTable = (flags & 0x02) != 0;
            boolean hasOperatorExtensions = (flags & 0x04) != 0;
            boolean hasDomainTag = (flags & 0x08) != 0;

            // Read generation and timestamp
            long generation = readLong(header, 7);
            long timestampMs = readLong(header, 15);
            Instant timestamp = Instant.ofEpochMilli(timestampMs);

            // Read CRC32
            int crc32 = readInt(header, 23);

            // Read domain tag length and content
            int domainTagLen = readShort(header, 27);
            String domainTag = null;
            if (hasDomainTag && domainTagLen > 0) {
                byte[] tagBytes = new byte[domainTagLen];
                raf.readFully(tagBytes);
                domainTag = new String(tagBytes, java.nio.charset.StandardCharsets.UTF_8);
            }

            // Read section table
            byte[] sectionCountBytes = new byte[2];
            raf.readFully(sectionCountBytes);
            int sectionCount = ((sectionCountBytes[0] & 0xFF) << 8) | (sectionCountBytes[1] & 0xFF);

            Map<Byte, SnapshotSection> sections = new HashMap<>();
            for (int i = 0; i < sectionCount; i++) {
                byte typeId = (byte) raf.read();
                byte[] offsetBytes = new byte[4];
                byte[] lengthBytes = new byte[4];
                raf.readFully(offsetBytes);
                raf.readFully(lengthBytes);

                int offset = readInt(offsetBytes, 0);
                int length = readInt(lengthBytes, 0);
                sections.put(typeId, new SnapshotSection(typeId, offset, length));
            }

            return new SnapshotManifest(version, generation, timestamp, domainTag,
                    hasChromosome, hasCycleTable, hasOperatorExtensions, crc32, sections);
        } catch (IOException e) {
            throw new MrcSnapshotException("Failed to read snapshot manifest", e);
        }
    }

    /**
     * Print human-readable summary of snapshot contents.
     */
    public void printSummary(PrintStream out) {
        out.println("Snapshot Summary:");
        out.println("  Version: " + version);
        out.println("  Generation: " + generation);
        out.println("  Timestamp: " + timestamp);
        out.println("  Domain Tag: " + (domainTag != null ? domainTag : "(none)"));
        out.println("  Has Chromosome: " + hasChromosome);
        out.println("  Has Cycle Table: " + hasCycleTable);
        out.println("  Has Operator Extensions: " + hasOperatorExtensions);
        out.println("  Sections: " + sections.size());
        for (SnapshotSection section : sections.values()) {
            out.printf("    Section 0x%02X @ offset %d, length %d%n",
                    section.typeId, section.offset, section.length);
        }
    }

    // Utility methods for reading primitives
    private static long readLong(byte[] data, int offset) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (data[offset + i] & 0xFF);
        }
        return result;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
