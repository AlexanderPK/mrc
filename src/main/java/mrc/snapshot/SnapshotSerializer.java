package mrc.snapshot;

import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.Chromosome;
import mrc.evolution.GenerationResult;
import mrc.graph.CyclePath;
import mrc.graph.TransitionGraph;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Serializes MRC system state (graph, cycles, chromosome, history) to binary snapshot files.
 * Format: magic + version + generation + timestamp + CRC32 + sections.
 */
public class SnapshotSerializer {
    private final ExtendedOperatorLibrary lib;

    public SnapshotSerializer(ExtendedOperatorLibrary lib) {
        this.lib = lib;
    }

    /**
     * Write a snapshot file with all available data.
     */
    public void serialize(
            Path outputPath,
            long generation,
            TransitionGraph graph,
            List<CyclePath> cycles,
            Chromosome bestChromosome,
            List<GenerationResult> evolutionHistory,
            String domainTag
    ) throws IOException, MrcSnapshotException {
        // Build sections
        Map<Byte, byte[]> sections = new HashMap<>();

        // TRANSITION_GRAPH section
        if (graph != null) {
            sections.put(SnapshotManifest.SnapshotSection.TRANSITION_GRAPH,
                    serializeTransitionGraph(graph));
        }

        // CYCLE_TABLE section
        if (cycles != null && !cycles.isEmpty()) {
            sections.put(SnapshotManifest.SnapshotSection.CYCLE_TABLE,
                    serializeCycles(cycles));
        }

        // CHROMOSOME section
        boolean hasChromosome = false;
        if (bestChromosome != null) {
            sections.put(SnapshotManifest.SnapshotSection.CHROMOSOME,
                    bestChromosome.toBytes());
            hasChromosome = true;
        }

        // EVOLUTION_STATS section
        if (evolutionHistory != null && !evolutionHistory.isEmpty()) {
            sections.put(SnapshotManifest.SnapshotSection.EVOLUTION_STATS,
                    serializeEvolutionHistory(evolutionHistory));
        }

        // Write to file
        writeSnapshotFile(outputPath, generation, domainTag, hasChromosome,
                cycles != null && !cycles.isEmpty(), sections);
    }

    private void writeSnapshotFile(
            Path outputPath,
            long generation,
            String domainTag,
            boolean hasChromosome,
            boolean hasCycles,
            Map<Byte, byte[]> sections
    ) throws IOException, MrcSnapshotException {
        try (RandomAccessFile raf = new RandomAccessFile(outputPath.toFile(), "rw")) {
            // Header layout (32 bytes minimum)
            int domainTagLen = domainTag != null ? domainTag.length() : 0;
            byte[] header = new byte[29 + domainTagLen];

            // Magic bytes (MRC2)
            header[0] = 0x4D;
            header[1] = 0x52;
            header[2] = 0x43;
            header[3] = 0x32;

            // Version (2 bytes)
            writeTwoBytes(header, 4, 2, 0);

            // Flags
            int flags = 0;
            if (hasChromosome) flags |= 0x01;
            if (hasCycles) flags |= 0x02;
            if (false) flags |= 0x04; // hasOperatorExtensions
            if (domainTag != null) flags |= 0x08;
            header[6] = (byte) flags;

            // Generation (8 bytes)
            writeLong(header, 7, generation);

            // Timestamp (8 bytes)
            writeLong(header, 15, Instant.now().toEpochMilli());

            // CRC32 placeholder (4 bytes) - will be filled later
            writeInt(header, 23, 0);

            // Domain tag length (2 bytes)
            writeTwoBytes(header, 27, domainTagLen, 0);

            // Domain tag bytes
            if (domainTag != null) {
                System.arraycopy(domainTag.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        0, header, 29, domainTagLen);
            }

            // Write header
            raf.write(header);

            // Calculate section offsets
            int sectionCount = sections.size();
            int sectionTableSize = 2 + (sectionCount * 9); // 2 for count, 9 per section
            int dataOffset = header.length + sectionTableSize;

            Map<Byte, Integer> offsets = new HashMap<>();
            int currentOffset = dataOffset;
            for (Byte typeId : sections.keySet()) {
                offsets.put(typeId, currentOffset);
                currentOffset += sections.get(typeId).length;
            }

            // Write section table
            raf.writeShort(sectionCount);
            for (Byte typeId : sections.keySet()) {
                raf.write(typeId);
                raf.writeInt(offsets.get(typeId));
                raf.writeInt(sections.get(typeId).length);
            }

            // Write section data
            for (Byte typeId : sections.keySet()) {
                raf.write(sections.get(typeId));
            }

            // Compute CRC32 and write back
            byte[] fileBytes = new byte[(int) raf.length()];
            raf.seek(0);
            raf.readFully(fileBytes);

            CRC32 crc = new CRC32();
            crc.update(fileBytes, 29, fileBytes.length - 29);
            int crc32 = (int) crc.getValue();

            raf.seek(23);
            raf.writeInt(crc32);
            raf.close();

            // Sync to disk
            Files.write(outputPath, fileBytes, StandardOpenOption.WRITE);
        }
    }

    private byte[] serializeTransitionGraph(TransitionGraph graph) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // For now, write empty (graph serialization is complex)
        dos.writeInt(0); // edge count

        dos.close();
        return baos.toByteArray();
    }

    private byte[] serializeCycles(List<CyclePath> cycles) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeShort(cycles.size());
        for (CyclePath cycle : cycles) {
            List<Integer> nodes = cycle.nodes();
            dos.writeByte(nodes.size());
            for (int node : nodes) {
                dos.writeByte(node);
            }
            for (var edge : cycle.edges()) {
                byte opId = (byte) mrc.core.OpIdMap.getOpId(edge.op());
                dos.writeByte(opId);
            }
            dos.writeDouble(cycle.totalWeight());
            dos.writeDouble(cycle.compressionGain());
        }

        dos.close();
        return baos.toByteArray();
    }

    private byte[] serializeEvolutionHistory(List<GenerationResult> history) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Cap at last 1000 generations
        List<GenerationResult> toWrite = history.size() > 1000 ?
                history.subList(history.size() - 1000, history.size()) :
                history;

        dos.writeInt(toWrite.size());
        for (GenerationResult result : toWrite) {
            dos.writeLong(result.generation());
            dos.writeLong(result.timestamp().toEpochMilli());
            dos.writeDouble(result.bestFitness());
            dos.writeDouble(result.averageFitness());
            dos.writeDouble(result.fitnessVariance());
            dos.writeInt(result.uniqueOperatorsUsed());
            dos.writeLong(result.corpusBytesProcessed());
            dos.writeLong(0); // reserved
        }

        dos.close();
        return baos.toByteArray();
    }

    // Utility write methods
    private void writeLong(byte[] data, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            data[offset + i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
    }

    private void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private void writeTwoBytes(byte[] data, int offset, int value, int unused) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

}
