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

package ricassiocosta.me.valv5.security;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A wrapper for char arrays (typically passwords) that provides automatic secure wiping.
 * 
 * Usage:
 * <pre>
 * try (SecureCharArray secure = new SecureCharArray(passwordChars)) {
 *     // Use secure.getData() for operations
 *     keySpec = new PBEKeySpec(secure.getData(), salt, iterations, keyLength);
 * } // Automatically wiped on close
 * </pre>
 */
public class SecureCharArray implements AutoCloseable {
    
    private char[] data;
    private volatile boolean wiped = false;
    private final boolean paranoidMode;
    
    /**
     * Create a new SecureCharArray with the specified size.
     */
    public SecureCharArray(int size) {
        this(size, false);
    }
    
    /**
     * Create a new SecureCharArray with the specified size.
     * @param size The size of the array
     * @param paranoidMode If true, use multiple wipe patterns
     */
    public SecureCharArray(int size, boolean paranoidMode) {
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative");
        }
        this.data = new char[size];
        this.paranoidMode = paranoidMode;
        SecureMemoryManager.getInstance().register(this.data);
    }
    
    /**
     * Create a SecureCharArray wrapping an existing char array.
     * The original array will be wiped when this wrapper is closed.
     */
    public SecureCharArray(@NonNull char[] existingData) {
        this(existingData, false);
    }
    
    /**
     * Create a SecureCharArray wrapping an existing char array.
     * @param existingData The array to wrap
     * @param paranoidMode If true, use multiple wipe patterns
     */
    public SecureCharArray(@NonNull char[] existingData, boolean paranoidMode) {
        this.data = existingData;
        this.paranoidMode = paranoidMode;
        SecureMemoryManager.getInstance().register(this.data);
    }
    
    /**
     * Create a SecureCharArray as a copy of an existing array.
     * The copy will be wiped when closed, but the original is not affected.
     */
    public static SecureCharArray copyOf(@NonNull char[] source) {
        return copyOf(source, false);
    }
    
    /**
     * Create a SecureCharArray as a copy of an existing array.
     */
    public static SecureCharArray copyOf(@NonNull char[] source, boolean paranoidMode) {
        SecureCharArray secure = new SecureCharArray(source.length, paranoidMode);
        System.arraycopy(source, 0, secure.data, 0, source.length);
        return secure;
    }
    
    /**
     * Get the underlying char array.
     * @throws IllegalStateException if the array has been wiped
     */
    @NonNull
    public char[] getData() {
        if (wiped) {
            throw new IllegalStateException("SecureCharArray has been wiped");
        }
        return data;
    }
    
    /**
     * Get the length of the array.
     */
    public int length() {
        return data != null ? data.length : 0;
    }
    
    /**
     * Check if this array has been wiped.
     */
    public boolean isWiped() {
        return wiped;
    }
    
    /**
     * Convert to bytes using UTF-8 encoding.
     * The returned SecureByteArray should be closed after use.
     */
    @NonNull
    public SecureByteArray toBytes() {
        if (wiped) {
            throw new IllegalStateException("SecureCharArray has been wiped");
        }
        
        CharBuffer charBuffer = CharBuffer.wrap(data);
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
        
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        
        // Clear the intermediate buffer
        SecureMemoryManager.getInstance().wipeNow(byteBuffer);
        
        return new SecureByteArray(bytes, paranoidMode);
    }
    
    /**
     * Securely wipe the char array.
     * After calling this method, getData() will throw an exception.
     */
    public void wipe() {
        if (wiped || data == null) {
            return;
        }
        
        synchronized (this) {
            if (wiped) {
                return;
            }
            
            if (paranoidMode) {
                // Multiple passes with different patterns
                Arrays.fill(data, '\uFFFF');
                Arrays.fill(data, '\uAAAA');
                Arrays.fill(data, '\u5555');
            }
            // Final pass with null characters
            Arrays.fill(data, '\0');
            
            wiped = true;
        }
    }
    
    @Override
    public void close() {
        wipe();
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            wipe();
        } finally {
            super.finalize();
        }
    }
}
