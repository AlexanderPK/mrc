package mrc.snapshot;

/**
 * Exception thrown when snapshot operations fail (read, write, validation).
 */
public class MrcSnapshotException extends Exception {
    public MrcSnapshotException(String message) {
        super(message);
    }

    public MrcSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
