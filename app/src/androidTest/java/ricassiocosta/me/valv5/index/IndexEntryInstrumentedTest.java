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

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import ricassiocosta.me.valv5.data.FileType;

/**
 * Instrumentation tests for IndexEntry class.
 * 
 * These tests run on Android device/emulator to verify JSON serialization
 * with the Android JSON implementation.
 */
@RunWith(AndroidJUnit4.class)
public class IndexEntryInstrumentedTest {

    private static final String TEST_FILE_NAME = "aB3xY9zK2mN5pQ7rS1tU4vW6xZ8aB3cD";
    
    @Test
    public void testConstructorWithFolderPath() {
        IndexEntry entry = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_IMAGE, "folder1/folder2");
        
        assertEquals(TEST_FILE_NAME, entry.getFileName());
        assertEquals(FileType.TYPE_IMAGE, entry.getFileType());
        assertEquals("folder1/folder2", entry.getFolderPath());
        assertFalse(entry.isInRootFolder());
    }
    
    @Test
    public void testConstructorWithoutFolderPath() {
        IndexEntry entry = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_VIDEO);
        
        assertEquals(TEST_FILE_NAME, entry.getFileName());
        assertEquals(FileType.TYPE_VIDEO, entry.getFileType());
        assertEquals("", entry.getFolderPath());
        assertTrue(entry.isInRootFolder());
    }
    
    @Test
    public void testIsInRootFolder_true() {
        IndexEntry entry = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_GIF, "");
        assertTrue(entry.isInRootFolder());
    }
    
    @Test
    public void testIsInRootFolder_false() {
        IndexEntry entry = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_GIF, "subfolder");
        assertFalse(entry.isInRootFolder());
    }
    
    @Test
    public void testToJsonWithFolderPath() throws JSONException {
        IndexEntry entry = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_TEXT, "myFolder");
        
        JSONObject json = entry.toJson();
        
        assertEquals(FileType.TYPE_TEXT, json.getInt("t"));
        assertEquals("myFolder", json.getString("p"));
    }
    
    @Test
    public void testToJsonWithoutFolderPath() throws JSONException {
        IndexEntry entry = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_IMAGE, "");
        
        JSONObject json = entry.toJson();
        
        assertEquals(FileType.TYPE_IMAGE, json.getInt("t"));
        assertFalse(json.has("p"));  // Empty folder path should not be in JSON
    }
    
    @Test
    public void testFromJsonWithFolderPath() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("t", FileType.TYPE_VIDEO);
        json.put("p", "nested/folder");
        
        IndexEntry entry = IndexEntry.fromJson(TEST_FILE_NAME, json);
        
        assertEquals(TEST_FILE_NAME, entry.getFileName());
        assertEquals(FileType.TYPE_VIDEO, entry.getFileType());
        assertEquals("nested/folder", entry.getFolderPath());
    }
    
    @Test
    public void testFromJsonWithoutFolderPath() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("t", FileType.TYPE_GIF);
        
        IndexEntry entry = IndexEntry.fromJson(TEST_FILE_NAME, json);
        
        assertEquals(TEST_FILE_NAME, entry.getFileName());
        assertEquals(FileType.TYPE_GIF, entry.getFileType());
        assertEquals("", entry.getFolderPath());
        assertTrue(entry.isInRootFolder());
    }
    
    @Test
    public void testJsonRoundTrip() throws JSONException {
        IndexEntry original = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_TEXT, "folder/sub");
        
        JSONObject json = original.toJson();
        IndexEntry restored = IndexEntry.fromJson(TEST_FILE_NAME, json);
        
        assertEquals(original.getFileName(), restored.getFileName());
        assertEquals(original.getFileType(), restored.getFileType());
        assertEquals(original.getFolderPath(), restored.getFolderPath());
    }
    
    @Test
    public void testEquals_sameFileName() {
        IndexEntry entry1 = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_IMAGE, "folder1");
        IndexEntry entry2 = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_VIDEO, "folder2");
        
        // Equality is based on fileName only
        assertEquals(entry1, entry2);
    }
    
    @Test
    public void testEquals_differentFileName() {
        IndexEntry entry1 = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_IMAGE, "folder1");
        IndexEntry entry2 = new IndexEntry("differentFileNameHere12345678", FileType.TYPE_IMAGE, "folder1");
        
        assertNotEquals(entry1, entry2);
    }
    
    @Test
    public void testHashCode_sameFileName() {
        IndexEntry entry1 = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_IMAGE, "folder1");
        IndexEntry entry2 = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_VIDEO, "folder2");
        
        assertEquals(entry1.hashCode(), entry2.hashCode());
    }
    
    @Test
    public void testToString() {
        IndexEntry entry = new IndexEntry(TEST_FILE_NAME, FileType.TYPE_GIF, "myFolder");
        
        String str = entry.toString();
        
        assertTrue(str.contains(TEST_FILE_NAME));
        assertTrue(str.contains("myFolder"));
        assertTrue(str.contains(String.valueOf(FileType.TYPE_GIF)));
    }
    
    @Test
    public void testAllFileTypes() throws JSONException {
        int[] fileTypes = {
            FileType.TYPE_IMAGE,
            FileType.TYPE_GIF,
            FileType.TYPE_VIDEO,
            FileType.TYPE_TEXT
        };
        
        for (int fileType : fileTypes) {
            IndexEntry entry = new IndexEntry(TEST_FILE_NAME, fileType, "");
            assertEquals(fileType, entry.getFileType());
            
            // Test JSON round-trip
            JSONObject json = entry.toJson();
            IndexEntry restored = IndexEntry.fromJson(TEST_FILE_NAME, json);
            assertEquals(fileType, restored.getFileType());
        }
    }
}
