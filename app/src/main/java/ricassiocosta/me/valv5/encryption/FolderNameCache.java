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

package ricassiocosta.me.valv5.encryption;

import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Memory-only LRU cache for decrypted folder names.
 * 
 * This cache stores the mapping from encrypted folder names (base64url strings)
 * to their decrypted original names. The cache is:
 * 
 * - Memory-only: Never persisted to disk for security
 * - Cleared on lock: When the app locks, all cached data is wiped
 * - LRU-based: Automatically evicts least recently used entries when full
 * 
 * Thread-safety is provided by Android's LruCache implementation.
 */
public class FolderNameCache {
    
    private static final int MAX_CACHE_SIZE = 500; // Max folders to cache
    
    private static FolderNameCache instance;
    
    // Key: encrypted folder name (base64url), Value: decrypted original name
    private final LruCache<String, String> cache;
    
    private FolderNameCache() {
        cache = new LruCache<>(MAX_CACHE_SIZE);
    }
    
    @NonNull
    public static synchronized FolderNameCache getInstance() {
        if (instance == null) {
            instance = new FolderNameCache();
        }
        return instance;
    }
    
    /**
     * Get the decrypted name for an encrypted folder name.
     * 
     * @param encryptedName The base64url-encoded encrypted folder name
     * @return The decrypted original name, or null if not in cache
     */
    @Nullable
    public String get(@NonNull String encryptedName) {
        return cache.get(encryptedName);
    }
    
    /**
     * Store a decrypted folder name in the cache.
     * 
     * @param encryptedName The base64url-encoded encrypted folder name
     * @param decryptedName The original folder name
     */
    public void put(@NonNull String encryptedName, @NonNull String decryptedName) {
        cache.put(encryptedName, decryptedName);
    }
    
    /**
     * Check if an encrypted folder name is already in the cache.
     * 
     * @param encryptedName The base64url-encoded encrypted folder name
     * @return true if the name is cached, false otherwise
     */
    public boolean contains(@NonNull String encryptedName) {
        return cache.get(encryptedName) != null;
    }
    
    /**
     * Remove a specific entry from the cache.
     * 
     * @param encryptedName The base64url-encoded encrypted folder name to remove
     */
    public void remove(@NonNull String encryptedName) {
        cache.remove(encryptedName);
    }
    
    /**
     * Clear all cached folder names.
     * Called when the app locks to ensure no sensitive data remains in memory.
     */
    public void clear() {
        cache.evictAll();
    }
    
    /**
     * Get the current number of cached entries.
     * 
     * @return The number of folder names currently cached
     */
    public int size() {
        return cache.size();
    }
}
