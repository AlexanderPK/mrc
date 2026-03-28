package mrc.codec.v3;

import java.io.IOException;

/** Thrown when an MRC v0x03 stream cannot be parsed. */
public class MrcV3FormatException extends IOException {
    public MrcV3FormatException(String message) {
        super(message);
    }
    public MrcV3FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
