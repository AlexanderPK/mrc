package mrc.snapshotdb;

import mrc.snapshot.MrcSnapshotException;
import mrc.snapshot.SnapshotManifest;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Filesystem-backed, content-addressed snapshot store.
 *
 * <p>Layout: {@code <storeRoot>/<id[0..1]>/<id>.snap}
 * <br>A snapshot is identified by the hex-encoded SHA-256 of its file content.
 * Publishing the same file twice returns the same ID with no disk write.
 */
public class FileSnapshotStore implements SnapshotStore {

    private final Path storeRoot;
    private final SnapshotIndex index;

    /**
     * Create or open a store at the given directory.
     * The directory is created if it does not exist.
     */
    public FileSnapshotStore(Path storeRoot) throws IOException {
        this.storeRoot = storeRoot;
        Files.createDirectories(storeRoot);
        this.index = new SnapshotIndex(storeRoot);
    }

    @Override
    public String publish(Path snapshotFile) throws IOException {
        byte[] bytes = Files.readAllBytes(snapshotFile);
        String id = sha256Hex(bytes);

        if (exists(id)) {
            return id; // dedup: same content already stored
        }

        // Write to <root>/<id[0..1]>/<id>.snap
        Path dir = storeRoot.resolve(id.substring(0, 2));
        Files.createDirectories(dir);
        Path dest = dir.resolve(id + ".snap");

        // Atomic write: write to temp file then move
        Path tmp = dir.resolve(id + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);

        // Index: read domain tag from manifest (best-effort)
        String domainTag = readDomainTag(dest);
        index.add(id, domainTag);

        return id;
    }

    @Override
    public Path load(String snapshotId) throws IOException {
        Path file = resolvePath(snapshotId);
        if (!Files.exists(file)) {
            throw new SnapshotNotFoundException(snapshotId);
        }
        return file;
    }

    @Override
    public List<String> listByDomain(String domainTag) {
        return index.getByDomain(domainTag);
    }

    @Override
    public boolean exists(String snapshotId) {
        return Files.exists(resolvePath(snapshotId));
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Path resolvePath(String id) {
        return storeRoot.resolve(id.substring(0, 2)).resolve(id + ".snap");
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static String readDomainTag(Path snapshotFile) {
        try {
            SnapshotManifest manifest = SnapshotManifest.read(snapshotFile);
            return manifest.domainTag();
        } catch (MrcSnapshotException e) {
            return null; // non-fatal: unrecognised or legacy snapshot
        }
    }
}
