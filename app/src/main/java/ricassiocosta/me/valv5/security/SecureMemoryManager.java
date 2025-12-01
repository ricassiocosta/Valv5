/*
 * Valv-Android
 * Copyright (C) 2024 Arctosoft AB
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

package ricassiocosta.me.valv5.security;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ricassiocosta.me.valv5.utils.FileStuff;

/**
 * Centralized manager for secure memory operations.
 * 
 * This class provides:
 * - Secure wiping of sensitive byte arrays and char arrays
 * - Registration and tracking of sensitive buffers for cleanup
 * - Automatic cleanup on folder change and app close
 * - Bitmap memory clearing
 * 
 * Security considerations:
 * - All registered buffers are wiped with zeros
 * - Multiple wipe patterns can be applied for paranoid mode
 * - WeakReferences are used to avoid memory leaks
 * - Thread-safe operations
 */
public class SecureMemoryManager {
    private static final String TAG = "SecureMemoryManager";
    
    private static volatile SecureMemoryManager instance;
    private static final Object LOCK = new Object();
    
    // Track registered sensitive buffers using WeakReferences to avoid memory leaks
    private final Set<WeakReference<byte[]>> registeredByteArrays = 
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<WeakReference<char[]>> registeredCharArrays = 
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<WeakReference<ByteBuffer>> registeredByteBuffers = 
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<WeakReference<Bitmap>> registeredBitmaps = 
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Track LruCaches that need clearing
    private final Set<WeakReference<LruCache<?, ?>>> registeredCaches = 
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Flag to indicate if paranoid mode is enabled (multiple wipe patterns)
    private final AtomicBoolean paranoidMode = new AtomicBoolean(false);
    
    private SecureMemoryManager() {
        // Private constructor for singleton
    }
    
    public static SecureMemoryManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SecureMemoryManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Enable or disable paranoid mode.
     * In paranoid mode, buffers are wiped with multiple patterns.
     */
    public void setParanoidMode(boolean enabled) {
        paranoidMode.set(enabled);
    }
    
    public boolean isParanoidMode() {
        return paranoidMode.get();
    }
    
    // ==================== Registration Methods ====================
    
    /**
     * Register a byte array for secure wiping on cleanup.
     * The array will be automatically wiped when wipeAll() is called.
     */
    public void register(@Nullable byte[] buffer) {
        if (buffer != null && buffer.length > 0) {
            registeredByteArrays.add(new WeakReference<>(buffer));
        }
    }
    
    /**
     * Register a char array for secure wiping on cleanup.
     */
    public void register(@Nullable char[] buffer) {
        if (buffer != null && buffer.length > 0) {
            registeredCharArrays.add(new WeakReference<>(buffer));
        }
    }
    
    /**
     * Register a ByteBuffer for secure wiping on cleanup.
     */
    public void register(@Nullable ByteBuffer buffer) {
        if (buffer != null && buffer.capacity() > 0) {
            registeredByteBuffers.add(new WeakReference<>(buffer));
        }
    }
    
    /**
     * Register a Bitmap for clearing on cleanup.
     */
    public void register(@Nullable Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            registeredBitmaps.add(new WeakReference<>(bitmap));
        }
    }
    
    /**
     * Register an LruCache for clearing on cleanup.
     */
    public void registerCache(@Nullable LruCache<?, ?> cache) {
        if (cache != null) {
            registeredCaches.add(new WeakReference<>(cache));
        }
    }
    
    // ==================== Immediate Wipe Methods ====================
    
    /**
     * Securely wipe a byte array immediately.
     * This overwrites the array with zeros (and additional patterns in paranoid mode).
     */
    public void wipeNow(@Nullable byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return;
        }
        
        if (paranoidMode.get()) {
            // Multiple passes with different patterns
            Arrays.fill(buffer, (byte) 0xFF);
            Arrays.fill(buffer, (byte) 0xAA);
            Arrays.fill(buffer, (byte) 0x55);
        }
        // Final pass with zeros
        Arrays.fill(buffer, (byte) 0);
    }
    
    /**
     * Securely wipe a char array immediately.
     */
    public void wipeNow(@Nullable char[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return;
        }
        
        if (paranoidMode.get()) {
            Arrays.fill(buffer, '\uFFFF');
            Arrays.fill(buffer, '\uAAAA');
            Arrays.fill(buffer, '\u5555');
        }
        Arrays.fill(buffer, '\0');
    }
    
    /**
     * Securely wipe a ByteBuffer immediately.
     */
    public void wipeNow(@Nullable ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() == 0) {
            return;
        }
        
        try {
            buffer.clear();
            if (buffer.hasArray()) {
                wipeNow(buffer.array());
            } else {
                // Direct buffer - fill with zeros
                byte[] zeros = new byte[Math.min(buffer.capacity(), 8192)];
                buffer.position(0);
                while (buffer.remaining() > 0) {
                    int toWrite = Math.min(buffer.remaining(), zeros.length);
                    buffer.put(zeros, 0, toWrite);
                }
            }
            buffer.clear();
        } catch (Exception e) {
            SecureLog.w(TAG, "Failed to wipe ByteBuffer", e);
        }
    }
    
    /**
     * Securely wipe a CharBuffer immediately.
     */
    public void wipeNow(@Nullable CharBuffer buffer) {
        if (buffer == null || buffer.capacity() == 0) {
            return;
        }
        
        try {
            buffer.clear();
            if (buffer.hasArray()) {
                wipeNow(buffer.array());
            }
            buffer.clear();
        } catch (Exception e) {
            SecureLog.w(TAG, "Failed to wipe CharBuffer", e);
        }
    }
    
    /**
     * Clear a Bitmap's pixels and recycle it.
     */
    public void wipeNow(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        
        try {
            // Overwrite pixels with black before recycling
            bitmap.eraseColor(android.graphics.Color.BLACK);
            bitmap.recycle();
        } catch (Exception e) {
            SecureLog.w(TAG, "Failed to wipe Bitmap", e);
        }
    }
    
    // ==================== Bulk Cleanup Methods ====================
    
    /**
     * Wipe only the most sensitive buffers (byte arrays, char arrays, ByteBuffers).
     * Does NOT clear LruCaches or Bitmaps as they may be needed by other fragments.
     * Call this on folder change.
     */
    public void wipeSensitiveBuffers() {
        SecureLog.d(TAG, "Wiping sensitive buffers only");
        
        int wipedByteArrays = 0;
        int wipedCharArrays = 0;
        int wipedByteBuffers = 0;
        
        // Wipe byte arrays (keys, decrypted content)
        Iterator<WeakReference<byte[]>> byteIterator = registeredByteArrays.iterator();
        while (byteIterator.hasNext()) {
            WeakReference<byte[]> ref = byteIterator.next();
            byte[] buffer = ref.get();
            if (buffer != null) {
                wipeNow(buffer);
                wipedByteArrays++;
            }
            byteIterator.remove();
        }
        
        // Wipe char arrays (passwords)
        Iterator<WeakReference<char[]>> charIterator = registeredCharArrays.iterator();
        while (charIterator.hasNext()) {
            WeakReference<char[]> ref = charIterator.next();
            char[] buffer = ref.get();
            if (buffer != null) {
                wipeNow(buffer);
                wipedCharArrays++;
            }
            charIterator.remove();
        }
        
        // Wipe ByteBuffers
        Iterator<WeakReference<ByteBuffer>> bufferIterator = registeredByteBuffers.iterator();
        while (bufferIterator.hasNext()) {
            WeakReference<ByteBuffer> ref = bufferIterator.next();
            ByteBuffer buffer = ref.get();
            if (buffer != null) {
                wipeNow(buffer);
                wipedByteBuffers++;
            }
            bufferIterator.remove();
        }
        
        SecureLog.d(TAG, String.format(
                "Wiped sensitive: %d byte arrays, %d char arrays, %d ByteBuffers",
                wipedByteArrays, wipedCharArrays, wipedByteBuffers
        ));
    }
    
    /**
     * Wipe all registered buffers and clear caches.
     * Call this on app close/lock only.
     */
    public void wipeAll() {
        SecureLog.d(TAG, "Wiping all registered sensitive memory");
        
        int wipedByteArrays = 0;
        int wipedCharArrays = 0;
        int wipedByteBuffers = 0;
        int wipedBitmaps = 0;
        int clearedCaches = 0;
        
        // Wipe byte arrays
        Iterator<WeakReference<byte[]>> byteIterator = registeredByteArrays.iterator();
        while (byteIterator.hasNext()) {
            WeakReference<byte[]> ref = byteIterator.next();
            byte[] buffer = ref.get();
            if (buffer != null) {
                wipeNow(buffer);
                wipedByteArrays++;
            }
            byteIterator.remove();
        }
        
        // Wipe char arrays
        Iterator<WeakReference<char[]>> charIterator = registeredCharArrays.iterator();
        while (charIterator.hasNext()) {
            WeakReference<char[]> ref = charIterator.next();
            char[] buffer = ref.get();
            if (buffer != null) {
                wipeNow(buffer);
                wipedCharArrays++;
            }
            charIterator.remove();
        }
        
        // Wipe ByteBuffers
        Iterator<WeakReference<ByteBuffer>> bufferIterator = registeredByteBuffers.iterator();
        while (bufferIterator.hasNext()) {
            WeakReference<ByteBuffer> ref = bufferIterator.next();
            ByteBuffer buffer = ref.get();
            if (buffer != null) {
                wipeNow(buffer);
                wipedByteBuffers++;
            }
            bufferIterator.remove();
        }
        
        // Clear Bitmaps
        Iterator<WeakReference<Bitmap>> bitmapIterator = registeredBitmaps.iterator();
        while (bitmapIterator.hasNext()) {
            WeakReference<Bitmap> ref = bitmapIterator.next();
            Bitmap bitmap = ref.get();
            if (bitmap != null) {
                wipeNow(bitmap);
                wipedBitmaps++;
            }
            bitmapIterator.remove();
        }
        
        // Clear LruCaches
        Iterator<WeakReference<LruCache<?, ?>>> cacheIterator = registeredCaches.iterator();
        while (cacheIterator.hasNext()) {
            WeakReference<LruCache<?, ?>> ref = cacheIterator.next();
            LruCache<?, ?> cache = ref.get();
            if (cache != null) {
                cache.evictAll();
                clearedCaches++;
            }
            cacheIterator.remove();
        }
        
        // Force garbage collection to help clear unreferenced sensitive data
        System.gc();
        
        SecureLog.d(TAG, String.format(
                "Wiped: %d byte arrays, %d char arrays, %d ByteBuffers, %d Bitmaps, %d caches",
                wipedByteArrays, wipedCharArrays, wipedByteBuffers, wipedBitmaps, clearedCaches
        ));
    }
    
    /**
     * Perform full memory cleanup including Glide cache and app cache.
     * Call this when the app is closing or locking.
     */
    public void performFullCleanup(@Nullable Context context) {
        SecureLog.d(TAG, "Performing full memory cleanup");
        
        // Wipe all registered buffers
        wipeAll();
        
        if (context != null) {
            // Clear Glide memory cache (must be called on main thread)
            try {
                Glide.get(context).clearMemory();
            } catch (Exception e) {
                SecureLog.w(TAG, "Failed to clear Glide memory cache", e);
            }
            
            // Delete file cache
            FileStuff.deleteCache(context);
        }
        
        // Destroy ephemeral session key to invalidate all cached items
        EphemeralSessionKey.getInstance().destroy();
        
        // Force garbage collection
        System.gc();
    }
    
    /**
     * Called when navigating between folders.
     * Performs lighter cleanup than full cleanup.
     * Note: Does NOT clear Glide cache or LruCaches as they would affect other fragments.
     */
    public void onFolderChanged(@Nullable Context context) {
        SecureLog.d(TAG, "Folder changed - performing sensitive buffer cleanup");
        
        // Wipe only sensitive buffers (keys, passwords, decrypted content)
        // Do NOT clear caches or Glide - they are needed by other fragments
        wipeSensitiveBuffers();
    }
    
    /**
     * Remove stale (garbage collected) references.
     * Call this periodically to prevent memory leaks in the registry itself.
     */
    public void cleanupStaleReferences() {
        registeredByteArrays.removeIf(ref -> ref.get() == null);
        registeredCharArrays.removeIf(ref -> ref.get() == null);
        registeredByteBuffers.removeIf(ref -> ref.get() == null);
        registeredBitmaps.removeIf(ref -> ref.get() == null);
        registeredCaches.removeIf(ref -> ref.get() == null);
    }
    
    /**
     * Get statistics about registered items (for debugging).
     */
    public String getStats() {
        cleanupStaleReferences();
        return String.format(
                "Registered: %d byte arrays, %d char arrays, %d ByteBuffers, %d Bitmaps, %d caches",
                registeredByteArrays.size(),
                registeredCharArrays.size(),
                registeredByteBuffers.size(),
                registeredBitmaps.size(),
                registeredCaches.size()
        );
    }
}
