package mrc.codec;

import java.io.ByteArrayOutputStream;

/**
 * Bit-level output stream for writing variable-width values to a byte array.
 *
 * Supports writing individual bits, multi-bit values (MSB first), and whole bytes.
 * Pads the final byte with zeros on flush.
 */
public class BitStreamWriter {
    private final ByteArrayOutputStream buffer;
    private int bitBuffer = 0;
    private int bitCount = 0;
    private int totalBitsWritten = 0;

    /**
     * Construct a BitStreamWriter.
     */
    public BitStreamWriter() {
        this.buffer = new ByteArrayOutputStream();
    }

    /**
     * Write a single bit.
     *
     * @param bit 0 or 1
     */
    public void writeBit(int bit) {
        bitBuffer = (bitBuffer << 1) | (bit & 1);
        bitCount++;
        totalBitsWritten++;

        if (bitCount == 8) {
            buffer.write(bitBuffer);
            bitBuffer = 0;
            bitCount = 0;
        }
    }

    /**
     * Write multiple bits (MSB first).
     *
     * @param value the bits to write
     * @param count the number of bits to write (1..64)
     */
    public void writeBits(long value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            writeBit((int) ((value >> i) & 1));
        }
    }

    /**
     * Write an 8-bit value (convenience method).
     *
     * @param value the byte value (0..255)
     */
    public void writeByte(int value) {
        writeBits(value & 0xFF, 8);
    }

    /**
     * Flush any remaining bits (padding with zeros) and return the byte array.
     *
     * @return the complete bitstream as a byte array
     */
    public byte[] toByteArray() {
        flush();
        return buffer.toByteArray();
    }

    /**
     * Flush remaining bits by padding with zeros.
     */
    public void flush() {
        if (bitCount > 0) {
            bitBuffer = (bitBuffer << (8 - bitCount)) & 0xFF;
            buffer.write(bitBuffer);
            bitBuffer = 0;
            bitCount = 0;
        }
    }

    /**
     * Get the total number of bits written (including padding).
     *
     * @return total bits written
     */
    public int totalBitsWritten() {
        return totalBitsWritten + ((bitCount > 0) ? (8 - bitCount) : 0);
    }
}
