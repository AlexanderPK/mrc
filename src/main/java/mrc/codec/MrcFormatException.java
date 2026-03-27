package mrc.codec;

/**
 * Checked exception thrown when the bitstream format is invalid.
 *
 * Raised on invalid headers, truncated data, unknown opIds, or other
 * format violations that prevent successful decoding.
 */
public class MrcFormatException extends Exception {

    /**
     * Construct with a message.
     *
     * @param message the error message
     */
    public MrcFormatException(String message) {
        super(message);
    }

    /**
     * Construct with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying exception
     */
    public MrcFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
