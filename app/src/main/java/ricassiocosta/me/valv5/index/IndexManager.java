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

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.ref.WeakReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.utils.Settings;
import java.util.concurrent.atomic.AtomicInteger;

import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.interfaces.IOnProgress;
import ricassiocosta.me.valv5.security.SecureLog;
import ricassiocosta.me.valv5.security.SecureMemoryManager;

/**
 * Manages the encrypted file index for the vault.
 * 
 * The index maps file names (32-char alphanumeric) to their types,
 * enabling instant filtering without decrypting file metadata.
 * 
 * The index itself is stored as a regular encrypted V5 file with
 * contentType = "INDEX", making it indistinguishable from other files.
 * 
 * Thread-safe singleton registered with SecureMemoryManager for cleanup on lock.
 */
public class IndexManager {
    private static final String TAG = "IndexManager";
    
    // JSON keys for index file
    private static final String JSON_VERSION = "v";
    private static final String JSON_CREATED_AT = "c";
    private static final String JSON_UPDATED_AT = "u";
    private static final String JSON_ENTRIES = "e";
    
    // Current index format version
    private static final int INDEX_VERSION = 1;
    
    // Content type identifier for index files (stored in metadata)
    public static final String CONTENT_TYPE_INDEX = "INDEX";
    
    // Prefix for index file names (dot makes it a "hidden" file)
    private static final String INDEX_FILE_PREFIX = ".";
    
    // Singleton instance
    private static IndexManager instance;
    
    // In-memory cache: fileName -> IndexEntry
    private final Map<String, IndexEntry> indexCache = new ConcurrentHashMap<>();
    
    // Name of the index file in the root folder (null if no index exists)
    @Nullable
    private String indexFileName = null;
    
    // URI of the index file
    @Nullable
    private Uri indexFileUri = null;
    
    // Timestamp of last index update
    private long lastUpdatedAt = 0;
    
    // Flag indicating if index has unsaved changes
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    
    // Flag indicating if index is currently loading
    private final AtomicBoolean loading = new AtomicBoolean(false);
    
    // Flag indicating if index has been loaded
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    
    private IndexManager() {
        // Register cache with SecureMemoryManager for cleanup on lock
        SecureMemoryManager.getInstance().registerMap(indexCache);
    }
    
    public static synchronized IndexManager getInstance() {
        if (instance == null) {
            instance = new IndexManager();
        }
        return instance;
    }
    
    /**
     * Check if a file is an index file by reading its encrypted metadata.
     * This is a public method for use by FileStuff to filter out index files from gallery.
     * 
     * @param context Context for content resolver
     * @param fileUri URI of the file to check
     * @param password User password for decryption
     * @return true if file is an index file, false otherwise
     */
    public boolean checkIsIndexFile(@NonNull Context context, @NonNull Uri fileUri, @NonNull char[] password) {
        return isIndexFile(context, fileUri, password);
    }
    
    /**
     * Check if the index has been loaded.
     */
    public boolean isLoaded() {
        return loaded.get();
    }
    
    /**
     * Check if the index is currently loading.
     */
    public boolean isLoading() {
        return loading.get();
    }
    
    /**
     * Check if the given file name is an index file (starts with dot prefix).
     * Used to filter out index files from gallery display.
     * 
     * @param fileName The file name to check
     * @return true if this is an index file, false otherwise
     */
    public boolean isIndexFileName(@Nullable String fileName) {
        if (fileName == null) return false;
        // Check if it matches the index file pattern: dot + 32 alphanumeric
        return fileName.length() == 33 && 
               fileName.startsWith(INDEX_FILE_PREFIX) && 
               fileName.substring(1).matches("[a-zA-Z0-9]{32}");
    }
    
    /**
     * Get the file type for a given file name.
     * 
     * @param fileName The 32-char file name (without path)
     * @return The file type, or -1 if not in index
     */
    public int getType(@NonNull String fileName) {
        IndexEntry entry = indexCache.get(fileName);
        return entry != null ? entry.getFileType() : -1;
    }
    
    /**
     * Get the full entry for a given file name.
     * 
     * @param fileName The 32-char file name (without path)
     * @return The IndexEntry, or null if not in index
     */
    @Nullable
    public IndexEntry getEntry(@NonNull String fileName) {
        return indexCache.get(fileName);
    }
    
    /**
     * Add or update an entry in the index.
     * 
     * @param fileName The 32-char file name
     * @param fileType The file type (FileType.TYPE_IMAGE, etc.)
     * @param folderPath Relative path to folder (empty for root)
     */
    public void addEntry(@NonNull String fileName, int fileType, @NonNull String folderPath) {
        IndexEntry entry = new IndexEntry(fileName, fileType, folderPath);
        indexCache.put(fileName, entry);
        dirty.set(true);
        lastUpdatedAt = System.currentTimeMillis();
        SecureLog.d(TAG, "addEntry: added " + fileName + " type=" + fileType);
        // Schedule a debounced autosave only when an index was previously loaded
        // to avoid persisting partial/placeholder entries during initial lazy scan.
        if (loaded.get()) {
            scheduleAutoSave();
        }
    }
    
    /**
     * Add or update an entry in the index (root folder).
     */
    public void addEntry(@NonNull String fileName, int fileType) {
        addEntry(fileName, fileType, "");
    }
    
    /**
     * Remove an entry from the index.
     * 
     * @param fileName The 32-char file name to remove
     * @return true if entry was removed, false if not found
     */
    public boolean removeEntry(@NonNull String fileName) {
        IndexEntry removed = indexCache.remove(fileName);
        if (removed != null) {
            dirty.set(true);
            lastUpdatedAt = System.currentTimeMillis();
            SecureLog.d(TAG, "removeEntry: removed " + fileName);
            // Schedule autosave when entries are removed, but only if an index was loaded
            if (loaded.get()) {
                scheduleAutoSave();
            }
            return true;
        }
        return false;
    }
    
    /**
     * Get count of entries in the index.
     */
    public int getEntryCount() {
        return indexCache.size();
    }
    
    /**
     * Get all entries matching a specific file type.
     * 
     * @param fileType The file type to filter by
     * @return List of matching entries
     */
    @NonNull
    public List<IndexEntry> getEntriesByType(int fileType) {
        List<IndexEntry> result = new ArrayList<>();
        for (IndexEntry entry : indexCache.values()) {
            if (entry.getFileType() == fileType) {
                result.add(entry);
            }
        }
        return result;
    }
    
    /**
     * Check if an entry exists in the index.
     */
    public boolean hasEntry(@NonNull String fileName) {
        return indexCache.containsKey(fileName);
    }
    
    /**
     * Clear the in-memory index cache.
     * Called when app is locked.
     */
    public void clear() {
        indexCache.clear();
        indexFileName = null;
        indexFileUri = null;
        lastUpdatedAt = 0;
        dirty.set(false);
        loaded.set(false);
        loading.set(false);
        // Stop autosave when clearing the cache to avoid holding activity references
        stopAutoSave();
        // Re-register with SecureMemoryManager for next session
        SecureMemoryManager.getInstance().registerMap(indexCache);
        SecureLog.d(TAG, "clear: index cache cleared");
    }

    // --- Autosave support (debounced) ---
    private ScheduledExecutorService autosaveExecutor = null;
    private ScheduledFuture<?> autosaveFuture = null;
    private WeakReference<FragmentActivity> autosaveActivityRef = null;
    private static final long AUTOSAVE_DELAY_MS = 2000L; // 2 seconds

    /**
     * Start autosave mechanism. Stores a weak reference to the activity for save operations.
     */
    public synchronized void startAutoSave(@NonNull FragmentActivity activity) {
        if (autosaveExecutor == null || autosaveExecutor.isShutdown()) {
            autosaveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "IndexManager-Autosave");
                t.setDaemon(true);
                return t;
            });
        }
        autosaveActivityRef = new WeakReference<>(activity);
        SecureLog.d(TAG, "startAutoSave: autosave started");
    }

    /**
     * Stop autosave and release resources.
     */
    public synchronized void stopAutoSave() {
        if (autosaveFuture != null && !autosaveFuture.isDone()) {
            autosaveFuture.cancel(false);
            autosaveFuture = null;
        }
        if (autosaveExecutor != null && !autosaveExecutor.isShutdown()) {
            autosaveExecutor.shutdownNow();
            autosaveExecutor = null;
        }
        if (autosaveActivityRef != null) {
            autosaveActivityRef.clear();
            autosaveActivityRef = null;
        }
        SecureLog.d(TAG, "stopAutoSave: autosave stopped");
    }

    /**
     * Schedule a debounced save operation using the stored activity and current password.
     */
    private synchronized void scheduleAutoSave() {
        if (autosaveExecutor == null) return;
        // Cancel previous scheduled save
        if (autosaveFuture != null && !autosaveFuture.isDone()) {
            autosaveFuture.cancel(false);
            autosaveFuture = null;
        }

        autosaveFuture = autosaveExecutor.schedule(() -> {
            try {
                FragmentActivity activity = autosaveActivityRef == null ? null : autosaveActivityRef.get();
                if (activity == null) return;

                char[] password = Password.getInstance().getPassword();
                if (password == null) return;

                List<Uri> roots = Settings.getInstance(activity).getGalleryDirectoriesAsUri(true);
                if (roots == null || roots.isEmpty()) return;
                Uri rootUri = roots.get(0);

                // Perform save if dirty
                if (isDirty()) {
                    saveIfDirty(activity, rootUri, password);
                }
            } catch (Exception e) {
                SecureLog.e(TAG, "scheduleAutoSave: error", e);
            }
        }, AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Load the index from the vault root folder.
     * Scans all files to find the index file (contentType = INDEX).
     * 
     * @param context Android context
     * @param rootUri URI of the vault root folder
     * @param password Encryption password
     * @return true if index was loaded, false if not found or error
     */
    public boolean loadIndex(@NonNull Context context, @NonNull Uri rootUri, @NonNull char[] password) {
        if (loading.getAndSet(true)) {
            SecureLog.d(TAG, "loadIndex: already loading");
            return false;
        }
        
        try {
            SecureLog.d(TAG, "loadIndex: scanning for index file in root");
            
            // Scan root folder for files
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    rootUri, DocumentsContract.getDocumentId(rootUri));
            
            Cursor cursor = context.getContentResolver().query(
                    childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    },
                    null, null, null);
            
            if (cursor == null) {
                SecureLog.e(TAG, "loadIndex: cursor is null");
                return false;
            }
            
            try {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    
                    // Skip directories
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        continue;
                    }
                    
                    // Check if this looks like a V5 file (32-char alphanumeric, no extension)
                    if (!isV5FileName(name)) {
                        continue;
                    }
                    
                    // Try to read metadata to check if this is the index file
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, docId);
                    
                    if (isIndexFile(context, fileUri, password)) {
                        SecureLog.d(TAG, "loadIndex: found index file: " + name);
                        indexFileName = name;
                        indexFileUri = fileUri;
                        
                        // Parse the index content
                        if (parseIndexFile(context, fileUri, password)) {
                            loaded.set(true);
                            dirty.set(false);
                            SecureLog.d(TAG, "loadIndex: loaded " + indexCache.size() + " entries");
                            return true;
                        }
                    }
                }
            } finally {
                cursor.close();
            }
            
            SecureLog.d(TAG, "loadIndex: no index file found");
            return false;
            
        } catch (Exception e) {
            SecureLog.e(TAG, "loadIndex: error", e);
            return false;
        } finally {
            loading.set(false);
        }
    }
    
    /**
     * Check if a file name looks like a V5 encrypted file or index file.
     */
    private boolean isV5FileName(@NonNull String name) {
        // Regular V5 file: 32 alphanumeric chars
        if (name.length() == 32 && name.matches("[a-zA-Z0-9]{32}")) {
            return true;
        }
        // Index file: dot prefix + 32 alphanumeric chars
        if (name.length() == 33 && name.startsWith(INDEX_FILE_PREFIX) && 
            name.substring(1).matches("[a-zA-Z0-9]{32}")) {
            return true;
        }
        return false;
    }
    
    /**
     * Check if a file is an index file by reading its metadata.
     */
    private boolean isIndexFile(@NonNull Context context, @NonNull Uri fileUri, @NonNull char[] password) {
        InputStream raw = null;
        Encryption.Streams streams = null;
        try {
            raw = context.getContentResolver().openInputStream(fileUri);
            if (raw == null) return false;

            try (InputStream is = raw) {
                streams = Encryption.getCipherInputStream(is, password, false, Encryption.ENCRYPTION_VERSION_5);
                String contentType = streams.getContentTypeString();
                return CONTENT_TYPE_INDEX.equals(contentType);
            }

        } catch (Exception e) {
            return false;
        } finally {
            if (streams != null) {
                try {
                    streams.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
    
    /**
     * Parse the index file content and populate the cache.
     */
    private boolean parseIndexFile(@NonNull Context context, @NonNull Uri fileUri, @NonNull char[] password) {
        InputStream raw = null;
        Encryption.Streams streams = null;
        try {
            raw = context.getContentResolver().openInputStream(fileUri);
            if (raw == null) return false;

            try (InputStream is = raw) {
                streams = Encryption.getCipherInputStream(is, password, false, Encryption.ENCRYPTION_VERSION_5);

                InputStream composite = streams.getCompositeFileStream();
                if (composite == null) {
                    return false;
                }

                try (InputStream fileStream = composite) {
                    // Read all bytes from file stream
                    byte[] contentBytes = readAllBytes(fileStream);
                    String jsonContent = new String(contentBytes, StandardCharsets.UTF_8);

                    // Clear sensitive byte[] buffer
                    java.util.Arrays.fill(contentBytes, (byte) 0);

                    // Parse JSON
                    JSONObject json = new JSONObject(jsonContent);

                    int version = json.optInt(JSON_VERSION, 1);
                    lastUpdatedAt = json.optLong(JSON_UPDATED_AT, System.currentTimeMillis());

                    JSONObject entries = json.optJSONObject(JSON_ENTRIES);
                    if (entries != null) {
                        Iterator<String> keys = entries.keys();
                        while (keys.hasNext()) {
                            String fileName = keys.next();
                            JSONObject entryJson = entries.getJSONObject(fileName);
                            IndexEntry entry = IndexEntry.fromJson(fileName, entryJson);
                            indexCache.put(fileName, entry);
                        }
                    }

                    SecureLog.d(TAG, "parseIndexFile: parsed version " + version + " with " + indexCache.size() + " entries");
                    return true;
                }
            }

        } catch (Exception e) {
            SecureLog.e(TAG, "parseIndexFile: error", e);
            return false;
        } finally {
            if (streams != null) {
                try {
                    streams.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
    
    /**
     * Save the index to disk.
     * Creates a new index file or updates the existing one.
     * 
     * @param activity FragmentActivity context
     * @param rootUri URI of the vault root folder
     * @param password Encryption password
     * @return true if saved successfully
     */
    public boolean saveIndex(@NonNull FragmentActivity activity, @NonNull Uri rootUri, @NonNull char[] password) {
        if (indexCache.isEmpty()) {
            SecureLog.d(TAG, "saveIndex: cache is empty, nothing to save");
            return true;
        }
        
        try {
            // Build JSON content
            JSONObject json = new JSONObject();
            json.put(JSON_VERSION, INDEX_VERSION);
            json.put(JSON_CREATED_AT, indexFileUri != null ? lastUpdatedAt : System.currentTimeMillis());
            json.put(JSON_UPDATED_AT, System.currentTimeMillis());
            
            JSONObject entriesJson = new JSONObject();
            for (Map.Entry<String, IndexEntry> entry : indexCache.entrySet()) {
                entriesJson.put(entry.getKey(), entry.getValue().toJson());
            }
            json.put(JSON_ENTRIES, entriesJson);
            
            String jsonContent = json.toString();
            byte[] contentBytes = jsonContent.getBytes(StandardCharsets.UTF_8);
            
            // Get root folder DocumentFile
            DocumentFile rootFolder = DocumentFile.fromTreeUri(activity, rootUri);
            if (rootFolder == null) {
                SecureLog.e(TAG, "saveIndex: could not get root folder");
                return false;
            }
            
            // Delete old index file if exists
            if (indexFileUri != null && indexFileName != null) {
                DocumentFile oldIndexFile = findFileByName(rootFolder, indexFileName);
                if (oldIndexFile != null && oldIndexFile.exists()) {
                    oldIndexFile.delete();
                    SecureLog.d(TAG, "saveIndex: deleted old index file");
                }
            }
            
            // Create new index file with random name
            String newFileName = generateRandomFileName();
            
            // Create the encrypted index file using V5 format
            DocumentFile indexFile = createIndexFile(
                    activity,
                    rootFolder,
                    contentBytes,
                    password,
                    newFileName);
            
            // Clear sensitive data
            java.util.Arrays.fill(contentBytes, (byte) 0);
            
            if (indexFile != null) {
                indexFileName = newFileName;
                indexFileUri = indexFile.getUri();
                dirty.set(false);
                loaded.set(true);
                SecureLog.d(TAG, "saveIndex: saved index with " + indexCache.size() + " entries");
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            SecureLog.e(TAG, "saveIndex: error", e);
            return false;
        }
    }
    
    /**
     * Check if there are unsaved changes.
     */
    public boolean isDirty() {
        return dirty.get();
    }
    
    /**
     * Save index if there are unsaved changes.
     */
    public boolean saveIfDirty(@NonNull FragmentActivity activity, @NonNull Uri rootUri, @NonNull char[] password) {
        if (!dirty.get()) {
            return true;
        }
        return saveIndex(activity, rootUri, password);
    }
    
    /**
     * Generate index for all files in the vault.
     * Recursively scans all folders and reads file metadata to determine types.
     * 
     * @param activity FragmentActivity context
     * @param rootUri URI of the vault root folder
     * @param password Encryption password
     * @param onProgress Progress callback (optional)
     * @param cancelled Cancellation flag
     * @return Number of files indexed, or -1 on error
     */
    public int generateIndex(
            @NonNull FragmentActivity activity,
            @NonNull Uri rootUri,
            @NonNull char[] password,
            @Nullable IOnProgress onProgress,
            @NonNull AtomicBoolean cancelled) {
        
        SecureLog.d(TAG, "generateIndex: starting smart index generation");
        
        // Load existing index first (if any) to avoid re-reading already indexed files
        int existingEntries = indexCache.size();
        if (existingEntries == 0) {
            loadIndex(activity, rootUri, password);
            existingEntries = indexCache.size();
        }
        SecureLog.d(TAG, "generateIndex: starting with " + existingEntries + " existing entries");
        
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger processedFiles = new AtomicInteger(0);
        
        try {
            // First pass: count files
            countFilesRecursive(activity, rootUri, totalFiles, cancelled);
            
            if (cancelled.get()) {
                // Save partial progress before returning
                SecureLog.d(TAG, "generateIndex: cancelled during counting, saving partial index");
                saveIndex(activity, rootUri, password);
                return indexCache.size();
            }
            
            SecureLog.d(TAG, "generateIndex: found " + totalFiles.get() + " files to process");
            
            // Second pass: index files (skips already indexed ones)
            indexFolderRecursive(activity, rootUri, "", password, totalFiles.get(), processedFiles, onProgress, cancelled);
            
            if (cancelled.get()) {
                // Save partial progress before returning
                int newEntries = indexCache.size() - existingEntries;
                SecureLog.d(TAG, "generateIndex: cancelled, saving partial index with " + newEntries + " new entries");
                saveIndex(activity, rootUri, password);
                return indexCache.size();
            }
            
            int newEntries = indexCache.size() - existingEntries;
            SecureLog.d(TAG, "generateIndex: added " + newEntries + " new entries");
            
            // Save the index
            if (saveIndex(activity, rootUri, password)) {
                SecureLog.d(TAG, "generateIndex: completed, total " + indexCache.size() + " files");
                return indexCache.size();
            }
            
            return -1;
            
        } catch (Exception e) {
            SecureLog.e(TAG, "generateIndex: error", e);
            // Try to save partial progress even on error
            saveIndex(activity, rootUri, password);
            return -1;
        }
    }
    
    /**
     * Count files recursively (for progress tracking).
     */
    private void countFilesRecursive(
            @NonNull Context context,
            @NonNull Uri folderUri,
            @NonNull AtomicInteger count,
            @NonNull AtomicBoolean cancelled) {
        
        if (cancelled.get()) return;
        
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folderUri, DocumentsContract.getDocumentId(folderUri));
            
            Cursor cursor = context.getContentResolver().query(
                    childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    },
                    null, null, null);
            
            if (cursor == null) return;
            
            try {
                while (cursor.moveToNext() && !cancelled.get()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        // Recurse into subdirectory
                        Uri subFolderUri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, docId);
                        countFilesRecursive(context, subFolderUri, count, cancelled);
                    } else if (isV5FileName(name)) {
                        count.incrementAndGet();
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            SecureLog.e(TAG, "countFilesRecursive: error", e);
        }
    }
    
    /**
     * Index files in a folder recursively.
     */
    private void indexFolderRecursive(
            @NonNull Context context,
            @NonNull Uri folderUri,
            @NonNull String folderPath,
            @NonNull char[] password,
            int totalFiles,
            @NonNull AtomicInteger processedFiles,
            @Nullable IOnProgress onProgress,
            @NonNull AtomicBoolean cancelled) {
        
        if (cancelled.get()) return;
        
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folderUri, DocumentsContract.getDocumentId(folderUri));
            
            Cursor cursor = context.getContentResolver().query(
                    childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    },
                    null, null, null);
            
            if (cursor == null) return;
            
            try {
                while (cursor.moveToNext() && !cancelled.get()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        // Recurse into subdirectory
                        Uri subFolderUri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, docId);
                        String subFolderPath = folderPath.isEmpty() ? name : folderPath + "/" + name;
                        indexFolderRecursive(context, subFolderUri, subFolderPath, password, totalFiles, processedFiles, onProgress, cancelled);
                    } else if (isV5FileName(name)) {
                        // Skip if this is the index file itself
                        if (isIndexFileName(name)) {
                            continue;
                        }
                        
                        // Skip if already in cache (smart regeneration)
                        if (indexCache.containsKey(name)) {
                            int processed = processedFiles.incrementAndGet();
                            if (onProgress != null) {
                                long progressPercent = totalFiles > 0 ? (processed * 100L / totalFiles) : 0;
                                onProgress.onProgress(progressPercent);
                            }
                            continue;
                        }
                        
                        // Index this file - need to read metadata
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, docId);
                        
                        int fileType = readFileType(context, fileUri, password);
                        if (fileType >= 0) {
                            indexCache.put(name, new IndexEntry(name, fileType, folderPath));
                        }
                        
                        int processed = processedFiles.incrementAndGet();
                        if (onProgress != null) {
                            // Calculate percentage progress (0-100)
                            long progressPercent = totalFiles > 0 ? (processed * 100L / totalFiles) : 0;
                            onProgress.onProgress(progressPercent);
                        }
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            SecureLog.e(TAG, "indexFolderRecursive: error in " + folderPath, e);
        }
    }
    
    /**
     * Read the file type from a V5 file's metadata.
     * 
     * @return File type, or -1 if error
     */
    private int readFileType(@NonNull Context context, @NonNull Uri fileUri, @NonNull char[] password) {
        InputStream raw = null;
        Encryption.Streams streams = null;
        try {
            raw = context.getContentResolver().openInputStream(fileUri);
            if (raw == null) return -1;

            try (InputStream is = raw) {
                streams = Encryption.getCipherInputStream(is, password, false, Encryption.ENCRYPTION_VERSION_5);

                int fileType = streams.getFileType();

                if (CONTENT_TYPE_INDEX.equals(streams.getContentTypeString())) {
                    return -1;
                }

                return fileType;
            }

        } catch (Exception e) {
            return -1;
        } finally {
            if (streams != null) {
                try {
                    streams.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Public wrapper to probe file type for external callers.
     * Returns the file type or -1 on error / unknown.
     */
    public int probeFileType(@NonNull Context context, @NonNull Uri fileUri, @NonNull char[] password) {
        return readFileType(context, fileUri, password);
    }
    
    /**
     * Create an encrypted index file.
     */
    @Nullable
    private DocumentFile createIndexFile(
            @NonNull FragmentActivity activity,
            @NonNull DocumentFile rootFolder,
            @NonNull byte[] content,
            @NonNull char[] password,
            @NonNull String fileName) throws GeneralSecurityException, IOException, JSONException {
        
        // Create the file
        DocumentFile file = rootFolder.createFile("application/octet-stream", fileName);
        if (file == null) {
            SecureLog.e(TAG, "createIndexFile: could not create file");
            return null;
        }
        
        // Write encrypted content using V5 format with INDEX content type
        Encryption.writeIndexFile(
                activity,
                new ByteArrayInputStream(content),
                content.length,
                file,
                password);
        
        return file;
    }
    
    /**
     * Generate a random file name with dot prefix for index file.
     * The dot prefix makes it a "hidden" file and distinguishes it from regular V5 files.
     */
    @NonNull
    private String generateRandomFileName() {
        SecureRandom random = new SecureRandom();
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(33);
        sb.append(INDEX_FILE_PREFIX); // Add dot prefix
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    /**
     * Find a file by name in a folder.
     */
    @Nullable
    private DocumentFile findFileByName(@NonNull DocumentFile folder, @NonNull String name) {
        for (DocumentFile file : folder.listFiles()) {
            if (name.equals(file.getName())) {
                return file;
            }
        }
        return null;
    }
    
    /**
     * Read all bytes from an input stream.
     */
    private byte[] readAllBytes(@NonNull InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        java.util.Arrays.fill(data, (byte) 0); // Clear temp buffer
        return buffer.toByteArray();
    }
}
