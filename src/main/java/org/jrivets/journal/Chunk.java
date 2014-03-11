package org.jrivets.journal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.jrivets.util.SyncUtils;

class Chunk {

    private final int id;

    private final File file;

    private final long capacity;

    private FileInputStream in;

    private FileOutputStream out;

    private volatile long bytesRead;

    private volatile long bytesWritten;

    private volatile int readers;

    Chunk(int id, long capacity, File file, boolean append) throws IOException {
        this.id = id;
        this.file = file;
        this.out = new FileOutputStream(file, append);
        if (append && file.exists()) {
            long position = file.length();
            this.capacity = Math.max(capacity, position);
            setWritePosition(position);
        } else {
            this.capacity = capacity;
        }
        this.in = new FileInputStream(file);
    }

    int getId() {
        return id;
    }

    long getReadPosition() {
        return bytesRead;
    }

    void setReadPosition(long position) throws IOException {
        position = Math.max(0L, position);
        in.getChannel().position(position);
        bytesRead = in.getChannel().position();
    }

    long getWritePosition() {
        return bytesWritten;
    }

    void setWritePosition(long position) throws IOException {
        position = Math.min(file.length(), Math.max(0L, position));
        out.getChannel().position(position);
        bytesWritten = position;
    }

    int read() throws IOException {
        int result = in.read();
        if (result >= 0) {
            ++bytesRead;
        }
        return result;
    }

    int read(byte b[], int off, int len) throws IOException {
        int result = in.read(b, off, len);
        if (result >= 0) {
            bytesRead += result;
        }
        return result;
    }

    long skip(long n) throws IOException {
        long result = in.skip(Math.max(n, 0L));
        bytesRead = in.getChannel().position();
        return result;
    }

    int available() throws IOException {
        return (int) Math.max(0, bytesWritten - bytesRead);
    }

    boolean write(int b) throws IOException {
        if (!isReadyToWrite()) {
            return false;
        }
        out.write(b);
        ++bytesWritten;
        notifyReaders();
        return true;
    }

    void delete() {
        close();
        file.delete();
    }

    int write(byte b[], int off, int len) throws IOException {
        if (!isReadyToWrite()) {
            return 0;
        }

        len = (int) Math.min(len, capacity - bytesWritten);
        out.write(b, off, len);
        bytesWritten += len;
        notifyReaders();
        return len;
    }

    void flush() throws IOException {
        out.flush();
    }

    synchronized void waitDataToRead() throws IOException {
        ++readers;
        try {
            while (!isDone() && !isReadyToRead()) {
                if (!SyncUtils.waitQuietly(this)) {
                    return;
                }
            }
        } finally {
            --readers;
        }
    }

    boolean isReadyToRead() throws IOException {
        return bytesWritten > bytesRead;
    }

    boolean isDone() throws IOException {
        return bytesWritten <= bytesRead && !isReadyToWrite();
    }

    boolean isReadyToWrite() throws IOException {
        return bytesWritten < capacity;
    }

    void close() {
        IOUtils.closeQuietly(in);
        closeOut();
    }

    void closeOut() {
        IOUtils.closeQuietly(out);
        out = null;
    }

    long getCapacity() {
        return capacity;
    }

    private void notifyReaders() {
        if (readers > 0) {
            synchronized (this) {
                notifyAll();
            }
        }
    }

    @Override
    public String toString() {
        return new StringBuilder(150).append("Chunk{ id=").append(id).append(", capacity=").append(capacity)
                .append(", bytesRead=").append(bytesRead).append(", bytesWritten=").append(bytesWritten)
                .append(", readers=").append(readers).append("}").toString();
    }

}