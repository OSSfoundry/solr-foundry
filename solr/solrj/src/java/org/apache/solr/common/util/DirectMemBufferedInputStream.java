package org.apache.solr.common.util;

import org.agrona.BitUtil;
import org.agrona.concurrent.MappedResizeableBuffer;

import java.io.*;
import java.nio.ByteOrder;

public class DirectMemBufferedInputStream extends JavaBinInputStream implements DataInputInputStream, DataInput {

    private final MappedResizeableBuffer buffer;
    private int offset;
    private long length;
    private long position;

    public DirectMemBufferedInputStream(final MappedResizeableBuffer buffer, long length) {
        this.buffer = buffer;
        this.length = length;
    }

    @Override
    public int read() throws IOException {
        if (position + 1 > length) {
            throw new EOFException();
        }
        int b;

        b = buffer.getByte(offset + position);
        ++position;

        return b;
    }


    public int read(final byte[] dstBytes, final int dstOffset, final int length) throws EOFException {
        if (position + length > this.length) {
            throw new EOFException();
        }
        int bytesRead = length;

        buffer.getBytes(offset + position, dstBytes, dstOffset, length);
        position += bytesRead;

        return bytesRead;
    }

    public int available() {
        return (int) (length - position);
    }

    /**
     * The offset within the underlying buffer at which to start.
     *
     * @return offset within the underlying buffer at which to start.
     */
    public int offset() {
        return offset;
    }

    /**
     * The length of the underlying buffer to use
     *
     * @return length of the underlying buffer to use
     */
    public long length() {
        return length;
    }

    /**
     * The underlying buffer being wrapped.
     *
     * @return the underlying buffer being wrapped.
     */
    public MappedResizeableBuffer buffer() {
        return buffer;
    }

    public int position() {
        return (int) position;
    }

    public void position(long pos) {
        this.position = pos;
    }

    @Override
    public void readFully(byte b[]) throws IOException {
        readFully(b, 0, b.length);
    }

    @Override
    public void readFully(byte b[], int off, int len) throws IOException {
        buffer.getBytes(position, b, off, len);
        position += len;
    }

    @Override
    public int skipBytes(int n) throws IOException {
        position += n;
        return n;
    }

    @Override
    public boolean readBoolean() throws IOException {
        throw new UnsupportedEncodingException();
    }

    @Override
    public byte readByte() throws IOException {
        return buffer.getByte(position++);
    }

    @Override
    public int readUnsignedByte() throws IOException {
        int ch = read() & 0xff;
        if (ch < 0)
            throw new EOFException();
        return ch;
    }


    @Override
    public short readShort() throws IOException {
        var s = buffer.getShort(position, ByteOrder.LITTLE_ENDIAN);
        position += BitUtil.SIZE_OF_SHORT;
        return s;
    }

    @Override
    public int readUnsignedShort() throws IOException {
        var s = buffer.getShort(position, ByteOrder.LITTLE_ENDIAN);
        position += BitUtil.SIZE_OF_SHORT;
        return s;
    }

    @Override
    public char readChar() throws IOException {
        throw new UnsupportedEncodingException();
    }

    public int readInt() throws IOException {
        var i = buffer.getInt(position, ByteOrder.LITTLE_ENDIAN);
        position += BitUtil.SIZE_OF_INT;
        return i;
    }

    public int getInt() throws IOException {
        var i = buffer.getInt(position, ByteOrder.LITTLE_ENDIAN);
        position += BitUtil.SIZE_OF_INT;
        return i;
    }

    @Override
    public long readLong() throws IOException {
        var l = buffer.getLong(position, ByteOrder.LITTLE_ENDIAN);
        position += BitUtil.SIZE_OF_LONG;
        return l;
    }

    @Override
    public float readFloat() throws IOException {
        var f = buffer.getFloat(position, ByteOrder.LITTLE_ENDIAN);
        position += BitUtil.SIZE_OF_FLOAT;
        return f;
    }

    @Override
    public double readDouble() throws IOException {
        var d = buffer.getDouble(position, ByteOrder.LITTLE_ENDIAN);
        position += BitUtil.SIZE_OF_DOUBLE;
        return d;
    }

    @Override
    public String readLine() throws IOException {
        throw new UnsupportedEncodingException();
    }

    @Override
    public String readUTF() throws IOException {
        throw new UnsupportedEncodingException();
    }
}
