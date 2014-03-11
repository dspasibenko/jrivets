package org.jrivets.journal;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class ExpandableJournalFunctionalTest {

    private final static String PREFIX = "expandable";

    private Journal journal;

    @BeforeMethod
    public void setup() throws IOException {
        Collection<File> files = IOUtils.getFiles(IOUtils.temporaryDirectory, PREFIX);
        for (File file : files) {
            file.delete();
        }
        
        journal = new JournalBuilder().withMaxCapacity(100).withMaxChunkSize(10).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
    }

    @AfterMethod
    public void tearDown() {
        journal.close();
    }

    @Test
    public void chunksTest() throws IOException {
        byte[] array = getShuffledByteArray(53);
        journal.getOutputStream().write(array, 0, array.length);
        Collection<File> files = IOUtils.getFiles(IOUtils.temporaryDirectory, PREFIX);
        assertEquals(files.size(), array.length/10 + (array.length%10 > 0 ? 1 : 0) + 1);
    }
    
    @Test
    public void markTest() throws IOException {
        byte[] array = getShuffledByteArray(50);
        journal.getOutputStream().write(array, 0, array.length);
        journal.getInputStream().mark(50);
        
        byte[] read = new byte[array.length];
        assertEquals(read.length, journal.getInputStream().read(read, 0, read.length));
        assertTrue(Arrays.equals(array, read));
        assertEquals(journal.getInputStream().read(), -1);
        assertEquals(-1, journal.getInputStream().read(read, 0, read.length));
        journal.getInputStream().reset();
        read[0]++;
        assertEquals(journal.getInputStream().read(read, 0, read.length), read.length);
        assertTrue(Arrays.equals(array, read));
    }

    @Test
    public void markTest2() throws IOException {
        byte[] array = getOrderedByteArray(50);
        journal.getOutputStream().write(array, 0, array.length);
        
        byte[] read = new byte[array.length];
        assertEquals(22, journal.getInputStream().read(read, 0, 22));
        assertEquals(read[21], 21);
        journal.getInputStream().mark(100);        
        assertEquals(journal.getInputStream().read(read, 0, read.length), read.length - 22);
        assertEquals(read[0], 22);
        
        journal.getInputStream().reset();
        assertEquals(journal.getInputStream().read(), 22);
        assertEquals(journal.getInputStream().read(read, 0, read.length), read.length - 23);
        
        Collection<File> files = IOUtils.getFiles(IOUtils.temporaryDirectory, PREFIX);
        assertEquals(files.size(), 5);
    }

    @Test
    public void fullMarkTest() throws IOException {
        byte[] array = getOrderedByteArray(100);
        journal.getOutputStream().write(array, 0, array.length);
        journal.getInputStream().mark(array.length + 3);
        assertEquals(array.length, journal.getInputStream().read(array));
        assertEquals(journal.getInputStream().read(), -1);
    }
    
    @Test(expectedExceptions = {IllegalStateException.class})
    public void fullMarkWithBlockedReadTest() throws IOException {
        journal.close();
        journal = new JournalBuilder().withMaxCapacity(100).withMaxChunkSize(10).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).withBlockedInputStream(true).buildExpandable();
        
        byte[] array = getOrderedByteArray(100);
        journal.getOutputStream().write(array, 0, array.length);
        journal.getInputStream().mark(array.length + 3);
        assertEquals(array.length, journal.getInputStream().read(array));
        journal.getInputStream().read();
    }
    
    @Test(expectedExceptions={IOException.class})
    public void resetOverflowTest() throws IOException {
        byte[] array = getShuffledByteArray(50);
        journal.getOutputStream().write(array, 0, array.length);
        journal.getInputStream().mark(5);
        
        byte[] read = new byte[array.length];
        assertEquals(read.length, journal.getInputStream().read(read, 0, read.length));
        journal.getInputStream().reset();
        fail("Reset should throw");
    }
    
    @Test
    public void eofTest() throws IOException {
        assertEquals(journal.getInputStream().read(), -1);
        
        byte[] array = getShuffledByteArray(50);
        journal.getOutputStream().write(array, 0, array.length);
        
        byte[] read = new byte[array.length];
        assertEquals(read.length, journal.getInputStream().read(read, 0, read.length));
        assertTrue(Arrays.equals(array, read));
        assertEquals(journal.getInputStream().read(), -1);
        assertEquals(journal.getInputStream().read(read, 0, read.length), -1);
    }
    
    @Test(timeOut=10000L)
    public void noEofTest() throws IOException {
        journal.close();
        journal = new JournalBuilder().withMaxCapacity(100).withMaxChunkSize(10).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).withBlockedInputStream(true).buildExpandable();
        final byte[] read = new byte[1];
        final AtomicBoolean b = new AtomicBoolean();
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                b.set(true);
                try {
                    read[0] = (byte) journal.getInputStream().read();
                } catch (IOException e) {
                } finally {
                    b.set(false);
                }
            }
        }).start();
        
        while (!b.get()) Thread.yield();
        journal.getOutputStream().write(123);
        while (b.get()) Thread.yield();
        assertEquals(read[0], 123);
    }
    
    @Test
    public void continuousReadTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        int read = 0;
        for (int i = 0; i < 10; i++) {
            journal.getOutputStream().write(array, 0, array.length);
            while (journal.getInputStream().read() != -1) {
                read++;
            }
        }
        assertEquals(read, array.length*10);
    }
    
    @Test
    public void openCloseEmptyTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        journal.getOutputStream().write(array);
        byte[] in = new byte[array.length];
        journal.getInputStream().read(in);
        assertFalse(in == array);
        assertTrue(Arrays.equals(array, in));
        assertEquals(journal.getInputStream().read(), -1);
        journal.close();
        
        Collection<File> files = IOUtils.getFiles(IOUtils.temporaryDirectory, PREFIX);
        assertEquals(files.size(), 2);
        journal = new JournalBuilder().withMaxCapacity(100).withMaxChunkSize(10).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
        assertEquals(journal.getInputStream().read(), -1);
        journal.getOutputStream().write(123);
        assertEquals(journal.getInputStream().read(), 123);
    }

    @Test
    public void openCloseSomeTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        journal.getOutputStream().write(array);
        journal.getInputStream().mark(100);
        journal.close();
        Collection<File> files = IOUtils.getFiles(IOUtils.temporaryDirectory, PREFIX);
        assertEquals(files.size(), 11);

        journal = new JournalBuilder().withMaxCapacity(101).withMaxChunkSize(10).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
        byte[] in = new byte[array.length];
        assertEquals(100, journal.getInputStream().read(in));
        assertFalse(in == array);
        assertTrue(Arrays.equals(array, in));
        assertEquals(journal.getInputStream().read(), -1);
        journal.close();       
    }

    @Test
    public void openCloseMarkSomeTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        journal.getOutputStream().write(array);
        
        byte[] in = new byte[array.length];
        assertEquals(15, journal.getInputStream().read(in, 0, 15));
        
        journal.getInputStream().mark(100);
        journal.close();
        Collection<File> files = IOUtils.getFiles(IOUtils.temporaryDirectory, PREFIX);
        assertEquals(files.size(), 10);

        journal = new JournalBuilder().withMaxCapacity(100).withMaxChunkSize(10).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
        assertEquals(85, journal.getInputStream().read(in));
        assertFalse(in == array);
        assertFalse(Arrays.equals(array, in));
        assertEquals(journal.getInputStream().read(), -1);
        journal.close();       
    }
    
    @Test
    public void readAllTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        journal.getOutputStream().write(array);
        byte[] in = new byte[array.length];
        assertEquals(100, journal.getInputStream().read(in));
        journal.close();

        journal = new JournalBuilder().withMaxCapacity(100).withMaxChunkSize(10).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
        assertFalse(in == array);
        assertTrue(Arrays.equals(array, in));
        assertEquals(journal.getInputStream().read(), -1);
        journal.close();       
    }
    
    @Test
    public void shrinkMaxSizeTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        journal.getOutputStream().write(array);
        journal.close();

        journal = new JournalBuilder().withMaxCapacity(20).withMaxChunkSize(5).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
        byte[] in = new byte[array.length];
        assertEquals(100, journal.getInputStream().read(in));
        journal.close();       
    }
    
    @Test(expectedExceptions = {IllegalStateException.class})
    public void lostChunkTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        journal.getOutputStream().write(array);
        journal.close();
        
        Collection<File> files = IOUtils.getFiles(IOUtils.temporaryDirectory, PREFIX + "1");
        assertEquals(files.size(), 1);
        files.iterator().next().delete();
        
        journal = new JournalBuilder().withMaxCapacity(20).withMaxChunkSize(5).withPrefixName(PREFIX)
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
    }
    
    @Test
    public void cleanUpChunkTest() throws IOException {
        byte[] array = getShuffledByteArray(100);
        journal.getOutputStream().write(array);
        journal.close();
        
        journal = new JournalBuilder().withMaxCapacity(20).withMaxChunkSize(5).withPrefixName(PREFIX).cleanAfterOpen()
                .withFolderName(IOUtils.temporaryDirectory).buildExpandable();
        
        assertEquals(-1, journal.getInputStream().read(array));
    }
    
    @Test(expectedExceptions = {FileNotFoundException.class})
    public void wrongFolderTest() throws IOException {
        journal.close();
        journal = new JournalBuilder().withMaxCapacity(20).withMaxChunkSize(5).withPrefixName(PREFIX).cleanAfterOpen()
                .withFolderName(IOUtils.temporaryDirectory + "1234ka983kjf13hkjahd").buildExpandable();
    }

    
    private byte[] getShuffledByteArray(int size) {
        byte[] array = new byte[size];
        new Random().nextBytes(array);
        return array;
    }
    
    private byte[] getOrderedByteArray(int size) {
        byte[] array = new byte[size];
        for (int i = 0; i < array.length; i++) {
            array[i] = (byte) i;
        }
        return array;
    }
}
