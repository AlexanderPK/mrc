package mrc.snapshot;

import mrc.core.OperatorLibrary;
import mrc.core.extended.ExtendedOperatorLibrary;
import mrc.evolution.Chromosome;
import mrc.evolution.GenerationResult;
import mrc.graph.CyclePath;
import mrc.graph.TransitionGraph;
import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Deserializes snapshot files back to MRC objects.
 * Validates CRC32, supports lazy-loading sections.
 */
public class SnapshotDeserializer {
    private final ExtendedOperatorLibrary lib;

    public SnapshotDeserializer(ExtendedOperatorLibrary lib) {
        this.lib = lib;
    }

    /**
     * Read manifest only (no section data loaded).
     */
    public SnapshotManifest readManifest(Path snapshotFile) throws MrcSnapshotException {
        return SnapshotManifest.read(snapshotFile);
    }

    /**
     * Load transition graph from snapshot.
     */
    public TransitionGraph loadGraph(Path snapshotFile, SnapshotManifest manifest)
            throws MrcSnapshotException, IOException {
        SnapshotManifest.SnapshotSection section =
                manifest.sections().get(SnapshotManifest.SnapshotSection.TRANSITION_GRAPH);
        if (section == null) {
            return new TransitionGraph();
        }

        try (RandomAccessFile raf = new RandomAccessFile(snapshotFile.toFile(), "r")) {
            raf.seek(section.offset());
            byte[] data = new byte[section.length()];
            raf.readFully(data);
            return deserializeTransitionGraph(data);
        }
    }

    /**
     * Load cycles from snapshot.
     */
    public List<CyclePath> loadCycles(Path snapshotFile, SnapshotManifest manifest)
            throws MrcSnapshotException, IOException {
        SnapshotManifest.SnapshotSection section =
                manifest.sections().get(SnapshotManifest.SnapshotSection.CYCLE_TABLE);
        if (section == null) {
            return new ArrayList<>();
        }

        try (RandomAccessFile raf = new RandomAccessFile(snapshotFile.toFile(), "r")) {
            raf.seek(section.offset());
            byte[] data = new byte[section.length()];
            raf.readFully(data);
            return deserializeCycles(data);
        }
    }

    /**
     * Load chromosome from snapshot.
     */
    public Chromosome loadChromosome(Path snapshotFile, SnapshotManifest manifest)
            throws MrcSnapshotException, IOException {
        SnapshotManifest.SnapshotSection section =
                manifest.sections().get(SnapshotManifest.SnapshotSection.CHROMOSOME);
        if (section == null) {
            return null;
        }

        try (RandomAccessFile raf = new RandomAccessFile(snapshotFile.toFile(), "r")) {
            raf.seek(section.offset());
            byte[] data = new byte[section.length()];
            raf.readFully(data);
            return Chromosome.fromBytes(data, (int) manifest.generation(), lib);
        }
    }

    /**
     * Load evolution history from snapshot.
     */
    public List<GenerationResult> loadEvolutionHistory(Path snapshotFile, SnapshotManifest manifest)
            throws MrcSnapshotException, IOException {
        SnapshotManifest.SnapshotSection section =
                manifest.sections().get(SnapshotManifest.SnapshotSection.EVOLUTION_STATS);
        if (section == null) {
            return new ArrayList<>();
        }

        try (RandomAccessFile raf = new RandomAccessFile(snapshotFile.toFile(), "r")) {
            raf.seek(section.offset());
            byte[] data = new byte[section.length()];
            raf.readFully(data);
            return deserializeEvolutionHistory(data);
        }
    }

    /**
     * Load everything in one call.
     */
    public record FullSnapshot(
            SnapshotManifest manifest,
            TransitionGraph graph,
            List<CyclePath> cycles,
            Chromosome chromosome,
            List<GenerationResult> history
    ) {}

    public FullSnapshot loadAll(Path snapshotFile)
            throws MrcSnapshotException, IOException {
        SnapshotManifest manifest = readManifest(snapshotFile);
        TransitionGraph graph = loadGraph(snapshotFile, manifest);
        List<CyclePath> cycles = loadCycles(snapshotFile, manifest);
        Chromosome chromosome = loadChromosome(snapshotFile, manifest);
        List<GenerationResult> history = loadEvolutionHistory(snapshotFile, manifest);

        return new FullSnapshot(manifest, graph, cycles, chromosome, history);
    }

    /**
     * Validate CRC32 of snapshot file.
     */
    public boolean validateCrc(Path snapshotFile) throws IOException, MrcSnapshotException {
        SnapshotManifest manifest = readManifest(snapshotFile);
        byte[] fileBytes = java.nio.file.Files.readAllBytes(snapshotFile);

        CRC32 crc = new CRC32();
        crc.update(fileBytes, 29, fileBytes.length - 29);
        int computedCrc = (int) crc.getValue();

        return computedCrc == manifest.crc32();
    }

    /**
     * Validate that chromosome rules are correct.
     */
    public boolean validateOperatorConsistency(FullSnapshot snapshot) {
        if (snapshot.chromosome() == null) {
            return true;
        }

        for (var rule : snapshot.chromosome().rules()) {
            int result = rule.assignedOperator().apply(rule.fromValue()) & 0xFF;
            if (result != rule.toValue()) {
                return false;
            }
        }
        return true;
    }

    // Private deserialization methods
    private TransitionGraph deserializeTransitionGraph(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int edgeCount = dis.readInt();
        // For now, return empty graph (full implementation would rebuild edges)
        return new TransitionGraph();
    }

    private List<CyclePath> deserializeCycles(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int cycleCount = dis.readShort();
        List<CyclePath> cycles = new ArrayList<>();

        for (int i = 0; i < cycleCount; i++) {
            int length = dis.readUnsignedByte();
            List<Integer> nodes = new ArrayList<>();
            for (int j = 0; j < length; j++) {
                nodes.add(dis.readUnsignedByte());
            }

            List<mrc.graph.TransitionEdge> edges = new ArrayList<>();
            OperatorLibrary opLib = OperatorLibrary.getInstance();
            for (int j = 0; j < length; j++) {
                byte opId = dis.readByte();
                var op = opLib.createOperator(opId, 0);
                if (op != null) {
                    mrc.graph.TransitionEdge edge = new mrc.graph.TransitionEdge(
                            nodes.get(j),
                            nodes.get((j + 1) % length),
                            op,
                            5 + op.operandBits(),
                            0,
                            0
                    );
                    edges.add(edge);
                }
            }

            double totalWeight = dis.readDouble();
            double gain = dis.readDouble();

            cycles.add(new CyclePath(nodes, edges, length, totalWeight, gain));
        }

        return cycles;
    }

    private List<GenerationResult> deserializeEvolutionHistory(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int count = dis.readInt();
        List<GenerationResult> history = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            long generation = dis.readLong();
            long timestampMs = dis.readLong();
            double bestFitness = dis.readDouble();
            double avgFitness = dis.readDouble();
            double variance = dis.readDouble();
            int uniqueOps = dis.readInt();
            long corpusBytes = dis.readLong();
            dis.readLong(); // reserved

            GenerationResult result = new GenerationResult(
                    generation,
                    null, // best chromosome not stored in history
                    bestFitness,
                    avgFitness,
                    variance,
                    0, // population size not stored
                    uniqueOps,
                    corpusBytes,
                    Instant.ofEpochMilli(timestampMs)
            );
            history.add(result);
        }

        return history;
    }
}
