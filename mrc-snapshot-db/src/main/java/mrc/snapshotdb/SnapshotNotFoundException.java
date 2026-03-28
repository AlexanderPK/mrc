package mrc.snapshotdb;

import java.io.IOException;

/**
 * Thrown when a snapshot with the requested ID is not found in the store.
 */
public class SnapshotNotFoundException extends IOException {

    private final String snapshotId;

    public SnapshotNotFoundException(String snapshotId) {
        super("Snapshot not found: " + snapshotId);
        this.snapshotId = snapshotId;
    }

    public String snapshotId() {
        return snapshotId;
    }
}
