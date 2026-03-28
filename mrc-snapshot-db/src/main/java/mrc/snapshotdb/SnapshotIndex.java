package mrc.snapshotdb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent index mapping snapshot IDs to domain tags.
 * Stored as a tab-delimited text file: {@code <id>\t<domain>} per line.
 * Domain may be empty if the snapshot has no domain tag.
 */
class SnapshotIndex {

    private static final String INDEX_FILE = "index.txt";
    private static final String NO_DOMAIN = "";

    private final Path indexFile;
    // domain -> list of IDs
    private final Map<String, List<String>> domainToIds = new ConcurrentHashMap<>();
    // id -> domain (for dedup check)
    private final Map<String, String> idToDomain = new ConcurrentHashMap<>();

    SnapshotIndex(Path storeRoot) throws IOException {
        this.indexFile = storeRoot.resolve(INDEX_FILE);
        load();
    }

    /** Add an ID with its domain tag. No-op if ID already registered. */
    synchronized void add(String id, String domainTag) throws IOException {
        if (idToDomain.containsKey(id)) {
            return; // already indexed
        }
        String domain = domainTag != null ? domainTag : NO_DOMAIN;
        idToDomain.put(id, domain);
        domainToIds.computeIfAbsent(domain, k -> new CopyOnWriteArrayList<>()).add(id);
        persist(id, domain);
    }

    /** List all IDs for a domain tag. Returns empty list if none. */
    List<String> getByDomain(String domainTag) {
        String domain = domainTag != null ? domainTag : NO_DOMAIN;
        List<String> ids = domainToIds.get(domain);
        return ids != null ? List.copyOf(ids) : List.of();
    }

    boolean contains(String id) {
        return idToDomain.containsKey(id);
    }

    private void load() throws IOException {
        if (!Files.exists(indexFile)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                String id = tab >= 0 ? line.substring(0, tab) : line;
                String domain = tab >= 0 ? line.substring(tab + 1) : NO_DOMAIN;
                idToDomain.put(id, domain);
                domainToIds.computeIfAbsent(domain, k -> new CopyOnWriteArrayList<>()).add(id);
            }
        }
    }

    private void persist(String id, String domain) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                indexFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(id + "\t" + domain);
            writer.newLine();
        }
    }
}
