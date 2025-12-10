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

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * A wrapper for byte arrays that provides automatic secure wiping.
 * 
 * Usage:
 * <pre>
 * try (SecureByteArray secure = new SecureByteArray(key)) {
 *     // Use secure.getData() for operations
 *     cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secure.getData(), "AES"));
 * } // Automatically wiped on close
 * </pre>
 * 
 * Or with explicit wiping:
 * <pre>
 * SecureByteArray secure = new SecureByteArray(32);
 * try {
 *     // ... use secure.getData()
 * } finally {
 *     secure.wipe();
 * }
 * </pre>
 */
public class SecureByteArray implements AutoCloseable {
    
    private byte[] data;
    private volatile boolean wiped = false;
    private final boolean paranoidMode;
    
    /**
     * Create a new SecureByteArray with the specified size.
     * The array is initialized with zeros.
     */
    public SecureByteArray(int size) {
        this(size, false);
    }
    
    /**
     * Create a new SecureByteArray with the specified size.
     * @param size The size of the array
     * @param paranoidMode If true, use multiple wipe patterns
     */
    public SecureByteArray(int size, boolean paranoidMode) {
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative");
        }
        this.data = new byte[size];
        this.paranoidMode = paranoidMode;
        SecureMemoryManager.getInstance().register(this.data);
    }
    
    /**
     * Create a SecureByteArray wrapping an existing byte array.
     * The original array will be wiped when this wrapper is closed.
     */
    public SecureByteArray(@NonNull byte[] existingData) {
        this(existingData, false);
    }
    
    /**
     * Create a SecureByteArray wrapping an existing byte array.
     * @param existingData The array to wrap
     * @param paranoidMode If true, use multiple wipe patterns
     */
    public SecureByteArray(@NonNull byte[] existingData, boolean paranoidMode) {
        this.data = existingData;
        this.paranoidMode = paranoidMode;
        SecureMemoryManager.getInstance().register(this.data);
    }
    
    /**
     * Create a SecureByteArray as a copy of an existing array.
     * The copy will be wiped when closed, but the original is not affected.
     */
    public static SecureByteArray copyOf(@NonNull byte[] source) {
        return copyOf(source, false);
    }
    
    /**
     * Create a SecureByteArray as a copy of an existing array.
     */
    public static SecureByteArray copyOf(@NonNull byte[] source, boolean paranoidMode) {
        SecureByteArray secure = new SecureByteArray(source.length, paranoidMode);
        System.arraycopy(source, 0, secure.data, 0, source.length);
        return secure;
    }
    
    /**
     * Get the underlying byte array.
     * @throws IllegalStateException if the array has been wiped
     */
    @NonNull
    public byte[] getData() {
        if (wiped) {
            throw new IllegalStateException("SecureByteArray has been wiped");
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
     * Securely wipe the byte array.
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
                Arrays.fill(data, (byte) 0xFF);
                Arrays.fill(data, (byte) 0xAA);
                Arrays.fill(data, (byte) 0x55);
            }
            // Final pass with zeros
            Arrays.fill(data, (byte) 0);
            
            wiped = true;
        }
    }
    
    /**
     * Copy data into this array at the specified offset.
     */
    public void copyFrom(@NonNull byte[] source, int srcOffset, int destOffset, int length) {
        if (wiped) {
            throw new IllegalStateException("SecureByteArray has been wiped");
        }
        System.arraycopy(source, srcOffset, data, destOffset, length);
    }
    
    /**
     * Copy data from this array.
     */
    public void copyTo(@NonNull byte[] dest, int srcOffset, int destOffset, int length) {
        if (wiped) {
            throw new IllegalStateException("SecureByteArray has been wiped");
        }
        System.arraycopy(data, srcOffset, dest, destOffset, length);
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
