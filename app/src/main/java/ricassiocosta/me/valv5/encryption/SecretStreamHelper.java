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

package ricassiocosta.me.valv5.encryption;

import android.util.Log;

import androidx.annotation.NonNull;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.SecretStream;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import ricassiocosta.me.valv5.exception.InvalidPasswordException;

/**
 * Helper class for libsodium's crypto_secretstream_xchacha20poly1305.
 * 
 * This provides streaming authenticated encryption that:
 * - Encrypts data in chunks with per-chunk authentication
 * - Detects truncation, reordering, and corruption
 * - Uses XChaCha20-Poly1305 (24-byte nonces, 256-bit keys)
 * - Memory efficient: only one chunk in memory at a time
 * 
 * Format:
 * - Header: 24 bytes (contains nonce state for encryption)
 * - Chunks: [ciphertext + 17-byte tag per chunk]
 *   - ABYTES = 17 (16-byte Poly1305 tag + 1 byte for tag type)
 * - Last chunk has TAG_FINAL to detect proper termination
 * 
 * Chunk overhead: 17 bytes per chunk (ABYTES)
 * Recommended chunk size: 64KB-1MB for balance of memory and performance
 */
public class SecretStreamHelper {
    private static final String TAG = "SecretStreamHelper";

    // Lazysodium instance (thread-safe singleton)
    private static LazySodiumAndroid lazySodium;
    
    // SecretStream constants
    public static final int HEADER_BYTES = SecretStream.HEADERBYTES; // 24 bytes
    public static final int A_BYTES = SecretStream.ABYTES; // 17 bytes (tag overhead per chunk)
    public static final int KEY_BYTES = SecretStream.KEYBYTES; // 32 bytes
    
    // Chunk sizes
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024; // 64 KB chunks
    public static final int MAX_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB max chunk
    
    // SecretStream tags (from libsodium)
    public static final byte TAG_MESSAGE = SecretStream.TAG_MESSAGE; // 0x00 - normal chunk
    public static final byte TAG_FINAL = SecretStream.TAG_FINAL; // 0x03 - last chunk
    public static final byte TAG_PUSH = SecretStream.TAG_PUSH; // 0x01 - rekey hint
    
    /**
     * Initialize lazysodium (call once at app startup or lazy init).
     */
    public static synchronized LazySodiumAndroid getLazySodium() {
        if (lazySodium == null) {
            SodiumAndroid sodium = new SodiumAndroid();
            lazySodium = new LazySodiumAndroid(sodium);
            Log.d(TAG, "Lazysodium initialized");
        }
        return lazySodium;
    }
    
    /**
     * Encrypt an input stream to an output stream using secretstream.
     * 
     * @param key 32-byte encryption key (from Argon2id)
     * @param plainInput Input stream with plaintext data
     * @param cipherOutput Output stream for ciphertext
     * @param chunkSize Size of each plaintext chunk (default 64KB)
     * @return Number of bytes written (including header and tags)
     * @throws IOException If I/O fails
     */
    public static long encrypt(@NonNull byte[] key, @NonNull InputStream plainInput,
                               @NonNull OutputStream cipherOutput, int chunkSize) throws IOException {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Key must be " + KEY_BYTES + " bytes, got " + key.length);
        }
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        
        LazySodiumAndroid ls = getLazySodium();
        
        // Initialize encryption state
        byte[] header = new byte[HEADER_BYTES];
        SecretStream.State state = new SecretStream.State();
        
        boolean initOk = ls.cryptoSecretStreamInitPush(state, header, key);
        if (!initOk) {
            throw new IOException("Failed to initialize secretstream encryption");
        }
        
        // Write header to output
        cipherOutput.write(header);
        long bytesWritten = HEADER_BYTES;
        
        // Encrypt in chunks
        byte[] plainChunk = new byte[chunkSize];
        byte[] cipherChunk = new byte[chunkSize + A_BYTES];
        int bytesRead;
        
        // Read first chunk to check if we have data
        bytesRead = readFully(plainInput, plainChunk, chunkSize);
        
        if (bytesRead <= 0) {
            // Empty file - write empty final chunk
            byte[] emptyCipher = new byte[A_BYTES];
            boolean pushOk = ls.cryptoSecretStreamPush(state, emptyCipher, new byte[0], 0, TAG_FINAL);
            if (!pushOk) {
                throw new IOException("Failed to encrypt final empty chunk");
            }
            cipherOutput.write(emptyCipher);
            bytesWritten += A_BYTES;
            return bytesWritten;
        }
        
        while (bytesRead > 0) {
            // Read next chunk to determine if current is final
            byte[] nextPlain = new byte[chunkSize];
            int nextRead = readFully(plainInput, nextPlain, chunkSize);
            
            // Determine tag based on whether there's more data
            byte tag = (nextRead <= 0) ? TAG_FINAL : TAG_MESSAGE;
            
            // Encrypt current chunk
            long[] cipherLen = new long[1];
            boolean pushOk = ls.getSodium().crypto_secretstream_xchacha20poly1305_push(
                    state,
                    cipherChunk, cipherLen,
                    plainChunk, bytesRead,
                    null, 0,  // no additional data
                    tag) == 0;
            
            if (!pushOk) {
                throw new IOException("Failed to encrypt chunk, tag=" + tag);
            }
            
            // Write ciphertext (exactly bytesRead + A_BYTES)
            int cipherWriteLen = bytesRead + A_BYTES;
            cipherOutput.write(cipherChunk, 0, cipherWriteLen);
            bytesWritten += cipherWriteLen;
            
            // Move to next chunk
            if (nextRead <= 0) {
                break;
            }
            
            // Swap buffers
            byte[] temp = plainChunk;
            plainChunk = nextPlain;
            nextPlain = temp;
            bytesRead = nextRead;
        }
        
        return bytesWritten;
    }
    
    /**
     * Encrypt with default chunk size (64KB).
     */
    public static long encrypt(@NonNull byte[] key, @NonNull InputStream plainInput,
                               @NonNull OutputStream cipherOutput) throws IOException {
        return encrypt(key, plainInput, cipherOutput, DEFAULT_CHUNK_SIZE);
    }
    
    /**
     * Decrypt a secretstream ciphertext to plaintext.
     * 
     * @param key 32-byte decryption key
     * @param cipherInput Input stream with ciphertext (header + chunks)
     * @param plainOutput Output stream for plaintext
     * @param chunkSize Size of ciphertext chunks (must match encryption)
     * @return Number of plaintext bytes written
     * @throws IOException If I/O fails
     * @throws InvalidPasswordException If authentication fails (wrong key or corrupted)
     */
    public static long decrypt(@NonNull byte[] key, @NonNull InputStream cipherInput,
                               @NonNull OutputStream plainOutput, int chunkSize) 
            throws IOException, InvalidPasswordException {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Key must be " + KEY_BYTES + " bytes, got " + key.length);
        }
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        
        LazySodiumAndroid ls = getLazySodium();
        
        // Read header
        byte[] header = new byte[HEADER_BYTES];
        int headerRead = readFully(cipherInput, header, HEADER_BYTES);
        if (headerRead != HEADER_BYTES) {
            throw new IOException("Truncated header: expected " + HEADER_BYTES + ", got " + headerRead);
        }
        
        // Initialize decryption state
        SecretStream.State state = new SecretStream.State();
        boolean initOk = ls.cryptoSecretStreamInitPull(state, header, key);
        if (!initOk) {
            throw new InvalidPasswordException("Failed to initialize secretstream decryption - invalid key or corrupted header");
        }
        
        // Decrypt in chunks
        int cipherChunkSize = chunkSize + A_BYTES;
        byte[] cipherChunk = new byte[cipherChunkSize];
        byte[] plainChunk = new byte[chunkSize];
        long bytesWritten = 0;
        
        while (true) {
            // Read ciphertext chunk
            int bytesRead = readFully(cipherInput, cipherChunk, cipherChunkSize);
            if (bytesRead <= 0) {
                throw new InvalidPasswordException("Stream ended without TAG_FINAL - file truncated or corrupted");
            }
            
            if (bytesRead < A_BYTES) {
                throw new InvalidPasswordException("Chunk too small: " + bytesRead + " < " + A_BYTES);
            }
            
            // Decrypt chunk
            long[] plainLen = new long[1];
            byte[] tagOut = new byte[1];
            
            int result = ls.getSodium().crypto_secretstream_xchacha20poly1305_pull(
                    state,
                    plainChunk, plainLen,
                    tagOut,
                    cipherChunk, bytesRead,
                    null, 0);  // no additional data
            
            if (result != 0) {
                throw new InvalidPasswordException("Decryption failed - wrong password or corrupted data");
            }
            
            // Write plaintext
            int plainWriteLen = (int) plainLen[0];
            if (plainWriteLen > 0) {
                plainOutput.write(plainChunk, 0, plainWriteLen);
                bytesWritten += plainWriteLen;
            }
            
            // Check if this was the final chunk
            if (tagOut[0] == TAG_FINAL) {
                break;
            }
        }
        
        return bytesWritten;
    }
    
    /**
     * Decrypt with default chunk size (64KB).
     */
    public static long decrypt(@NonNull byte[] key, @NonNull InputStream cipherInput,
                               @NonNull OutputStream plainOutput) throws IOException, InvalidPasswordException {
        return decrypt(key, cipherInput, plainOutput, DEFAULT_CHUNK_SIZE);
    }
    
    /**
     * Read up to 'length' bytes from input stream, handling partial reads.
     * Returns actual number of bytes read (may be less at end of stream).
     */
    private static int readFully(@NonNull InputStream in, @NonNull byte[] buffer, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int bytesRead = in.read(buffer, totalRead, length - totalRead);
            if (bytesRead < 0) {
                break;
            }
            totalRead += bytesRead;
        }
        return totalRead;
    }
    
    /**
     * Calculate ciphertext size for a given plaintext size.
     * 
     * @param plaintextSize Size of plaintext in bytes
     * @param chunkSize Size of each chunk
     * @return Total ciphertext size including header and all tags
     */
    public static long calculateCiphertextSize(long plaintextSize, int chunkSize) {
        if (plaintextSize <= 0) {
            // Empty file: header + one empty final chunk
            return HEADER_BYTES + A_BYTES;
        }
        
        // Number of full chunks + one partial/final chunk
        long numFullChunks = plaintextSize / chunkSize;
        long remainder = plaintextSize % chunkSize;
        long totalChunks = (remainder > 0) ? numFullChunks + 1 : numFullChunks;
        
        // Total = header + (numChunks * chunkSize) + (numChunks * A_BYTES)
        return HEADER_BYTES + plaintextSize + (totalChunks * A_BYTES);
    }
    
    /**
     * OutputStream wrapper that encrypts data using secretstream as it's written.
     * Buffers data internally and encrypts in chunks.
     * 
     * IMPORTANT: Must call finish() before close() to write the final TAG_FINAL chunk.
     */
    public static class SecretStreamOutputStream extends OutputStream {
        private final OutputStream cipherOutput;
        private final SecretStream.State state;
        private final LazySodiumAndroid ls;
        private final byte[] plainBuffer;
        private final byte[] cipherBuffer;
        private final int chunkSize;
        private int bufferPos = 0;
        private boolean finished = false;
        private boolean headerWritten = false;
        
        /**
         * Create a new encrypting output stream.
         * 
         * @param key 32-byte encryption key
         * @param cipherOutput Underlying output stream for ciphertext
         * @param chunkSize Size of plaintext chunks (default 64KB)
         */
        public SecretStreamOutputStream(@NonNull byte[] key, @NonNull OutputStream cipherOutput, int chunkSize) throws IOException {
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException("Key must be " + KEY_BYTES + " bytes");
            }
            if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
                chunkSize = DEFAULT_CHUNK_SIZE;
            }
            
            this.cipherOutput = cipherOutput;
            this.chunkSize = chunkSize;
            this.plainBuffer = new byte[chunkSize];
            this.cipherBuffer = new byte[chunkSize + A_BYTES];
            this.ls = getLazySodium();
            this.state = new SecretStream.State();
            
            // Initialize encryption state and write header
            byte[] header = new byte[HEADER_BYTES];
            boolean initOk = ls.cryptoSecretStreamInitPush(state, header, key);
            if (!initOk) {
                throw new IOException("Failed to initialize secretstream encryption");
            }
            
            cipherOutput.write(header);
            headerWritten = true;
        }
        
        /**
         * Create with default chunk size (64KB).
         */
        public SecretStreamOutputStream(@NonNull byte[] key, @NonNull OutputStream cipherOutput) throws IOException {
            this(key, cipherOutput, DEFAULT_CHUNK_SIZE);
        }
        
        @Override
        public void write(int b) throws IOException {
            if (finished) {
                throw new IOException("Stream already finished");
            }
            plainBuffer[bufferPos++] = (byte) b;
            if (bufferPos >= chunkSize) {
                flushChunk(TAG_MESSAGE);
            }
        }
        
        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            if (finished) {
                throw new IOException("Stream already finished");
            }
            
            int remaining = len;
            int srcPos = off;
            
            while (remaining > 0) {
                int spaceInBuffer = chunkSize - bufferPos;
                int toCopy = Math.min(remaining, spaceInBuffer);
                
                System.arraycopy(b, srcPos, plainBuffer, bufferPos, toCopy);
                bufferPos += toCopy;
                srcPos += toCopy;
                remaining -= toCopy;
                
                if (bufferPos >= chunkSize) {
                    flushChunk(TAG_MESSAGE);
                }
            }
        }
        
        /**
         * Flush a chunk with the specified tag.
         */
        private void flushChunk(byte tag) throws IOException {
            if (bufferPos == 0 && tag != TAG_FINAL) {
                return; // Nothing to flush
            }
            
            long[] cipherLen = new long[1];
            int result = ls.getSodium().crypto_secretstream_xchacha20poly1305_push(
                    state,
                    cipherBuffer, cipherLen,
                    plainBuffer, bufferPos,
                    null, 0,
                    tag);
            
            if (result != 0) {
                throw new IOException("Failed to encrypt chunk");
            }
            
            int writeLen = bufferPos + A_BYTES;
            cipherOutput.write(cipherBuffer, 0, writeLen);
            bufferPos = 0;
        }
        
        /**
         * Finish the stream by writing the final chunk with TAG_FINAL.
         * Must be called before close().
         */
        public void finish() throws IOException {
            if (finished) {
                return;
            }
            
            // Write final chunk (may be empty if buffer is empty)
            flushChunk(TAG_FINAL);
            finished = true;
        }
        
        @Override
        public void flush() throws IOException {
            // Don't flush partial chunks - they need to stay buffered
            // until finish() is called
            cipherOutput.flush();
        }
        
        @Override
        public void close() throws IOException {
            if (!finished) {
                finish();
            }
            cipherOutput.close();
        }
    }
    
    /**
     * InputStream wrapper that decrypts secretstream data as it's read.
     * Reads and decrypts chunks on demand, keeping only one chunk in memory.
     */
    public static class SecretStreamInputStream extends InputStream {
        private final InputStream cipherInput;
        private final SecretStream.State state;
        private final LazySodiumAndroid ls;
        private final byte[] cipherBuffer;
        private final byte[] plainBuffer;
        private final int chunkSize;
        private int plainBufferPos = 0;
        private int plainBufferLen = 0;
        private boolean finished = false;
        private boolean initialized = false;
        private final byte[] key;
        
        /**
         * Create a new decrypting input stream.
         * 
         * @param key 32-byte decryption key
         * @param cipherInput Underlying input stream with ciphertext
         * @param chunkSize Size of ciphertext chunks (must match encryption)
         */
        public SecretStreamInputStream(@NonNull byte[] key, @NonNull InputStream cipherInput, int chunkSize) throws IOException {
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException("Key must be " + KEY_BYTES + " bytes");
            }
            if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
                chunkSize = DEFAULT_CHUNK_SIZE;
            }
            
            this.key = key.clone();
            this.cipherInput = cipherInput;
            this.chunkSize = chunkSize;
            this.cipherBuffer = new byte[chunkSize + A_BYTES];
            this.plainBuffer = new byte[chunkSize];
            this.ls = getLazySodium();
            this.state = new SecretStream.State();
        }
        
        /**
         * Create with default chunk size (64KB).
         */
        public SecretStreamInputStream(@NonNull byte[] key, @NonNull InputStream cipherInput) throws IOException {
            this(key, cipherInput, DEFAULT_CHUNK_SIZE);
        }
        
        /**
         * Initialize the stream by reading the header.
         */
        private void ensureInitialized() throws IOException {
            if (initialized) {
                return;
            }
            
            byte[] header = new byte[HEADER_BYTES];
            int headerRead = readFully(cipherInput, header, HEADER_BYTES);
            if (headerRead != HEADER_BYTES) {
                throw new IOException("Truncated header: expected " + HEADER_BYTES + ", got " + headerRead);
            }
            
            boolean initOk = ls.cryptoSecretStreamInitPull(state, header, key);
            if (!initOk) {
                throw new IOException("Failed to initialize secretstream decryption - invalid key or corrupted header");
            }
            
            initialized = true;
        }
        
        /**
         * Read and decrypt the next chunk into plainBuffer.
         * Returns false if no more data.
         */
        private boolean readNextChunk() throws IOException {
            if (finished) {
                return false;
            }
            
            ensureInitialized();
            
            int cipherChunkSize = chunkSize + A_BYTES;
            int bytesRead = readFully(cipherInput, cipherBuffer, cipherChunkSize);
            
            if (bytesRead <= 0) {
                throw new IOException("Stream ended without TAG_FINAL - file truncated or corrupted");
            }
            
            if (bytesRead < A_BYTES) {
                throw new IOException("Chunk too small: " + bytesRead + " < " + A_BYTES);
            }
            
            // Decrypt chunk
            long[] plainLen = new long[1];
            byte[] tagOut = new byte[1];
            
            int result = ls.getSodium().crypto_secretstream_xchacha20poly1305_pull(
                    state,
                    plainBuffer, plainLen,
                    tagOut,
                    cipherBuffer, bytesRead,
                    null, 0);
            
            if (result != 0) {
                throw new IOException("Decryption failed - wrong password or corrupted data");
            }
            
            plainBufferPos = 0;
            plainBufferLen = (int) plainLen[0];
            
            if (tagOut[0] == TAG_FINAL) {
                finished = true;
            }
            
            return plainBufferLen > 0;
        }
        
        @Override
        public int read() throws IOException {
            if (plainBufferPos >= plainBufferLen) {
                if (!readNextChunk()) {
                    return -1;
                }
                if (plainBufferLen == 0) {
                    return -1;
                }
            }
            return plainBuffer[plainBufferPos++] & 0xFF;
        }
        
        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            
            int totalRead = 0;
            
            while (totalRead < len) {
                // Refill buffer if needed
                if (plainBufferPos >= plainBufferLen) {
                    if (!readNextChunk()) {
                        break;
                    }
                    if (plainBufferLen == 0) {
                        break;
                    }
                }
                
                // Copy from buffer
                int available = plainBufferLen - plainBufferPos;
                int toCopy = Math.min(available, len - totalRead);
                System.arraycopy(plainBuffer, plainBufferPos, b, off + totalRead, toCopy);
                plainBufferPos += toCopy;
                totalRead += toCopy;
            }
            
            return totalRead > 0 ? totalRead : -1;
        }
        
        @Override
        public int available() throws IOException {
            return plainBufferLen - plainBufferPos;
        }
        
        @Override
        public void close() throws IOException {
            // Clear key from memory
            java.util.Arrays.fill(key, (byte) 0);
            cipherInput.close();
        }
    }
}
