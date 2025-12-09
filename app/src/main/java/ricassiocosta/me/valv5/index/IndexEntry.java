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

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents a single entry in the encrypted file index.
 * Maps a file name (32-char random string) to its type and location.
 */
public class IndexEntry {
    
    private static final String JSON_FILE_TYPE = "t";
    private static final String JSON_FOLDER_PATH = "p";
    
    /** The file name (32-char alphanumeric, no extension) */
    @NonNull
    private final String fileName;
    
    /** The file type (FileType.TYPE_IMAGE, TYPE_GIF, TYPE_VIDEO, TYPE_TEXT) */
    private final int fileType;
    
    /** 
     * Relative path to the folder containing this file.
     * Empty string for files in root folder.
     * Example: "encryptedFolderName" or "folder1/folder2"
     */
    @NonNull
    private final String folderPath;
    
    public IndexEntry(@NonNull String fileName, int fileType, @NonNull String folderPath) {
        this.fileName = fileName;
        this.fileType = fileType;
        this.folderPath = folderPath;
    }
    
    public IndexEntry(@NonNull String fileName, int fileType) {
        this(fileName, fileType, "");
    }
    
    @NonNull
    public String getFileName() {
        return fileName;
    }
    
    public int getFileType() {
        return fileType;
    }
    
    @NonNull
    public String getFolderPath() {
        return folderPath;
    }
    
    /**
     * Check if this file is in the root folder.
     */
    public boolean isInRootFolder() {
        return folderPath.isEmpty();
    }
    
    /**
     * Convert to compact JSON object for storage.
     * Uses short keys to minimize file size.
     */
    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put(JSON_FILE_TYPE, fileType);
        if (!folderPath.isEmpty()) {
            json.put(JSON_FOLDER_PATH, folderPath);
        }
        return json;
    }
    
    /**
     * Create an IndexEntry from a JSON object.
     */
    @NonNull
    public static IndexEntry fromJson(@NonNull String fileName, @NonNull JSONObject json) throws JSONException {
        int fileType = json.getInt(JSON_FILE_TYPE);
        String folderPath = json.optString(JSON_FOLDER_PATH, "");
        return new IndexEntry(fileName, fileType, folderPath);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IndexEntry that = (IndexEntry) o;
        return fileName.equals(that.fileName);
    }
    
    @Override
    public int hashCode() {
        return fileName.hashCode();
    }
    
    @NonNull
    @Override
    public String toString() {
        return "IndexEntry{" +
                "fileName='" + fileName + '\'' +
                ", fileType=" + fileType +
                ", folderPath='" + folderPath + '\'' +
                '}';
    }
}
