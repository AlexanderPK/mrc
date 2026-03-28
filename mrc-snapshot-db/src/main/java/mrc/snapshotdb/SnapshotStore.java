package mrc.snapshotdb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Content-addressed, immutable snapshot store.
 * IDs are hex-encoded SHA-256 hashes of snapshot file bytes.
 * Once published, a snapshot can never be modified or deleted.
 */
public interface SnapshotStore {

    /**
     * Publish a snapshot file to the store.
     * If an identical file has already been published, returns the existing ID without re-writing.
     *
     * @param snapshotFile path to the snapshot file to publish
     * @return 64-char hex SHA-256 ID
     */
    String publish(Path snapshotFile) throws IOException;

    /**
     * Load a snapshot by its ID.
     *
     * @param snapshotId 64-char hex SHA-256 ID
     * @return path to the stored snapshot file (read-only)
     * @throws SnapshotNotFoundException if no snapshot with that ID exists
     */
    Path load(String snapshotId) throws IOException;

    /**
     * List all snapshot IDs that were published with the given domain tag.
     * Returns an empty list if no snapshots match.
     */
    List<String> listByDomain(String domainTag);

    /**
     * Check whether a snapshot with the given ID exists in the store.
     */
    boolean exists(String snapshotId);
}
