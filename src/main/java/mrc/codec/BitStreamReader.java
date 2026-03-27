package mrc.codec;

/**
 * Bit-level input stream for reading variable-width values from a byte array.
 *
 * Supports reading individual bits, multi-bit values (MSB first), and whole bytes.
 */
public class BitStreamReader {
    private final byte[] data;
    private int byteIndex = 0;
    private int bitIndex = 0;
    private int totalBitsRead = 0;

    /**
     * Construct a BitStreamReader from a byte array.
     *
     * @param data the byte array to read from
     */
    public BitStreamReader(byte[] data) {
        this.data = data != null ? data : new byte[0];
    }

    /**
     * Read a single bit.
     *
     * @return 0 or 1
     * @throws MrcFormatException if the stream is exhausted
     */
    public int readBit() throws MrcFormatException {
        if (!hasMore()) {
            throw new MrcFormatException("BitStream exhausted");
        }

        int bit = (data[byteIndex] >> (7 - bitIndex)) & 1;
        bitIndex++;
        totalBitsRead++;

        if (bitIndex == 8) {
            byteIndex++;
            bitIndex = 0;
        }

        return bit;
    }

    /**
     * Read multiple bits (MSB first).
     *
     * @param count the number of bits to read
     * @return the bits as a long value
     * @throws MrcFormatException if the stream is exhausted
     */
    public long readBits(int count) throws MrcFormatException {
        long result = 0;
        for (int i = 0; i < count; i++) {
            result = (result << 1) | readBit();
        }
        return result;
    }

    /**
     * Read an 8-bit value (convenience method).
     *
     * @return the byte value (0..255)
     * @throws MrcFormatException if the stream is exhausted
     */
    public int readByte() throws MrcFormatException {
        return (int) readBits(8);
    }

    /**
     * Check if there are more bits to read.
     *
     * @return true if more data is available
     */
    public boolean hasMore() {
        return byteIndex < data.length;
    }

    /**
     * Get the total number of bits read so far.
     *
     * @return total bits read
     */
    public int totalBitsRead() {
        return totalBitsRead;
    }
}
