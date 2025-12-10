/*
 * Valv5
 * Copyright (c) 2025 ricassiocosta.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see https://www.gnu.org/licenses/.
 */

package ricassiocosta.me.valv5.index;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import ricassiocosta.me.valv5.data.FileType;

/**
 * Instrumentation tests for IndexManager class.
 * 
 * These tests MUST run on Android device/emulator because:
 * - IndexManager uses SecureMemoryManager which requires Android context
 * - Native libraries and Android-specific APIs are used
 * 
 * Tests verify:
 * - Singleton pattern works correctly
 * - Adding, removing, and querying entries
 * - File name validation for index files
 * - Entry filtering by type
 * - Thread-safe concurrent access
 */
@RunWith(AndroidJUnit4.class)
public class IndexManagerInstrumentedTest {

    private IndexManager indexManager;
    
    private static final String TEST_FILE_1 = "aB3xY9zK2mN5pQ7rS1tU4vW6xZ8aB3cD";
    private static final String TEST_FILE_2 = "dE4fG0hI1jK2lM3nO4pQ5rS6tU7vW8xY";
    private static final String TEST_FILE_3 = "yZ9aB1cD2eF3gH4iJ5kL6mN7oP8qR9sT";
    
    @Before
    public void setUp() {
        indexManager = IndexManager.getInstance();
        // Clear the index before each test
        indexManager.clear();
    }
    
    @Test
    public void testSingletonInstance() {
        IndexManager instance1 = IndexManager.getInstance();
        IndexManager instance2 = IndexManager.getInstance();
        assertSame(instance1, instance2);
    }
    
    @Test
    public void testAddEntry_basic() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE, "");
        
        assertEquals(1, indexManager.getEntryCount());
        assertTrue(indexManager.hasEntry(TEST_FILE_1));
        assertEquals(FileType.TYPE_IMAGE, indexManager.getType(TEST_FILE_1));
    }
    
    @Test
    public void testAddEntry_withFolderPath() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_VIDEO, "folder/subfolder");
        
        IndexEntry entry = indexManager.getEntry(TEST_FILE_1);
        assertNotNull(entry);
        assertEquals(FileType.TYPE_VIDEO, entry.getFileType());
        assertEquals("folder/subfolder", entry.getFolderPath());
    }
    
    @Test
    public void testAddEntry_shortForm() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_GIF);
        
        IndexEntry entry = indexManager.getEntry(TEST_FILE_1);
        assertNotNull(entry);
        assertEquals(FileType.TYPE_GIF, entry.getFileType());
        assertEquals("", entry.getFolderPath());
    }
    
    @Test
    public void testAddEntry_overwrite() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE, "");
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_VIDEO, "newFolder");
        
        assertEquals(1, indexManager.getEntryCount());
        IndexEntry entry = indexManager.getEntry(TEST_FILE_1);
        assertNotNull(entry);
        assertEquals(FileType.TYPE_VIDEO, entry.getFileType());
        assertEquals("newFolder", entry.getFolderPath());
    }
    
    @Test
    public void testRemoveEntry_existing() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE);
        
        assertTrue(indexManager.removeEntry(TEST_FILE_1));
        assertFalse(indexManager.hasEntry(TEST_FILE_1));
        assertEquals(0, indexManager.getEntryCount());
    }
    
    @Test
    public void testRemoveEntry_nonExisting() {
        assertFalse(indexManager.removeEntry(TEST_FILE_1));
    }
    
    @Test
    public void testGetType_existing() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_GIF);
        assertEquals(FileType.TYPE_GIF, indexManager.getType(TEST_FILE_1));
    }
    
    @Test
    public void testGetType_nonExisting() {
        assertEquals(-1, indexManager.getType(TEST_FILE_1));
    }
    
    @Test
    public void testGetEntry_existing() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_TEXT, "docs");
        
        IndexEntry entry = indexManager.getEntry(TEST_FILE_1);
        assertNotNull(entry);
        assertEquals(TEST_FILE_1, entry.getFileName());
        assertEquals(FileType.TYPE_TEXT, entry.getFileType());
        assertEquals("docs", entry.getFolderPath());
    }
    
    @Test
    public void testGetEntry_nonExisting() {
        assertNull(indexManager.getEntry(TEST_FILE_1));
    }
    
    @Test
    public void testHasEntry() {
        assertFalse(indexManager.hasEntry(TEST_FILE_1));
        
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE);
        assertTrue(indexManager.hasEntry(TEST_FILE_1));
        
        indexManager.removeEntry(TEST_FILE_1);
        assertFalse(indexManager.hasEntry(TEST_FILE_1));
    }
    
    @Test
    public void testGetEntryCount() {
        assertEquals(0, indexManager.getEntryCount());
        
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE);
        assertEquals(1, indexManager.getEntryCount());
        
        indexManager.addEntry(TEST_FILE_2, FileType.TYPE_VIDEO);
        assertEquals(2, indexManager.getEntryCount());
        
        indexManager.addEntry(TEST_FILE_3, FileType.TYPE_GIF);
        assertEquals(3, indexManager.getEntryCount());
    }
    
    @Test
    public void testGetEntriesByType() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE);
        indexManager.addEntry(TEST_FILE_2, FileType.TYPE_IMAGE);
        indexManager.addEntry(TEST_FILE_3, FileType.TYPE_VIDEO);
        
        List<IndexEntry> images = indexManager.getEntriesByType(FileType.TYPE_IMAGE);
        assertEquals(2, images.size());
        
        List<IndexEntry> videos = indexManager.getEntriesByType(FileType.TYPE_VIDEO);
        assertEquals(1, videos.size());
        assertEquals(TEST_FILE_3, videos.get(0).getFileName());
        
        List<IndexEntry> gifs = indexManager.getEntriesByType(FileType.TYPE_GIF);
        assertEquals(0, gifs.size());
    }
    
    @Test
    public void testIsIndexFileName_valid() {
        // Valid index file: dot prefix + 32 alphanumeric
        String validIndexName = "." + TEST_FILE_1;
        assertTrue(indexManager.isIndexFileName(validIndexName));
    }
    
    @Test
    public void testIsIndexFileName_tooShort() {
        assertFalse(indexManager.isIndexFileName(".short"));
    }
    
    @Test
    public void testIsIndexFileName_tooLong() {
        assertFalse(indexManager.isIndexFileName(".aB3xY9zK2mN5pQ7rS1tU4vW6xZ8aB3cDExtra"));
    }
    
    @Test
    public void testIsIndexFileName_noDotPrefix() {
        // Regular file name without dot prefix (32 chars)
        assertFalse(indexManager.isIndexFileName(TEST_FILE_1));
    }
    
    @Test
    public void testIsIndexFileName_invalidChars() {
        // Contains invalid characters
        assertFalse(indexManager.isIndexFileName(".aB3xY9zK2mN5pQ7rS1tU4vW6xZ8aB_!"));
    }
    
    @Test
    public void testIsIndexFileName_null() {
        assertFalse(indexManager.isIndexFileName(null));
    }
    
    @Test
    public void testIsIndexFileName_empty() {
        assertFalse(indexManager.isIndexFileName(""));
    }
    
    @Test
    public void testIsLoaded_initial() {
        assertFalse(indexManager.isLoaded());
    }
    
    @Test
    public void testIsLoading_initial() {
        assertFalse(indexManager.isLoading());
    }
    
    @Test
    public void testClear() {
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE);
        indexManager.addEntry(TEST_FILE_2, FileType.TYPE_VIDEO);
        assertEquals(2, indexManager.getEntryCount());
        
        indexManager.clear();
        
        assertEquals(0, indexManager.getEntryCount());
        assertFalse(indexManager.hasEntry(TEST_FILE_1));
        assertFalse(indexManager.hasEntry(TEST_FILE_2));
        assertFalse(indexManager.isLoaded());
    }
    
    @Test
    public void testMultipleOperations() {
        // Add multiple entries
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_IMAGE);
        indexManager.addEntry(TEST_FILE_2, FileType.TYPE_VIDEO, "videos");
        indexManager.addEntry(TEST_FILE_3, FileType.TYPE_GIF, "gifs/animated");
        
        assertEquals(3, indexManager.getEntryCount());
        
        // Verify each
        assertEquals(FileType.TYPE_IMAGE, indexManager.getType(TEST_FILE_1));
        assertEquals(FileType.TYPE_VIDEO, indexManager.getType(TEST_FILE_2));
        assertEquals(FileType.TYPE_GIF, indexManager.getType(TEST_FILE_3));
        
        // Remove one
        assertTrue(indexManager.removeEntry(TEST_FILE_2));
        assertEquals(2, indexManager.getEntryCount());
        assertEquals(-1, indexManager.getType(TEST_FILE_2));
        
        // Update existing
        indexManager.addEntry(TEST_FILE_1, FileType.TYPE_TEXT, "texts");
        assertEquals(2, indexManager.getEntryCount());
        assertEquals(FileType.TYPE_TEXT, indexManager.getType(TEST_FILE_1));
        assertEquals("texts", indexManager.getEntry(TEST_FILE_1).getFolderPath());
    }
    
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        // Test thread safety with concurrent adds
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                indexManager.addEntry("fileA" + String.format("%027d", i), FileType.TYPE_IMAGE);
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                indexManager.addEntry("fileB" + String.format("%027d", i), FileType.TYPE_VIDEO);
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        assertEquals(200, indexManager.getEntryCount());
    }
    
    @Test
    public void testContentTypeConstant() {
        assertEquals("INDEX", IndexManager.CONTENT_TYPE_INDEX);
    }
}
