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

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;

import org.signal.argon2.Argon2;
import org.signal.argon2.MemoryCost;
import org.signal.argon2.Type;
import org.signal.argon2.Version;

import ricassiocosta.me.valv5.data.DirHash;
import ricassiocosta.me.valv5.data.FileType;
import ricassiocosta.me.valv5.data.GalleryFile;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.interfaces.IOnProgress;
import ricassiocosta.me.valv5.security.SecureMemoryManager;
import ricassiocosta.me.valv5.utils.FileStuff;
import ricassiocosta.me.valv5.utils.Settings;
import ricassiocosta.me.valv5.utils.StringStuff;
import ricassiocosta.me.valv5.security.SecureLog;

public class Encryption {
    private static final String TAG = "Encryption";
    private static final String CIPHER_LEGACY = "ChaCha20/NONE/NoPadding";
    private static final String CIPHER_AEAD = "ChaCha20-Poly1305";
    private static final String KEY_ALGORITHM = "PBKDF2withHmacSHA512";
    private static final int KEY_LENGTH = 256;
    public static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int CHECK_LENGTH = 12;
    private static final int POLY1305_TAG_LENGTH = 16;
    
    // Maximum file size for AEAD mode (in bytes)
    // Files larger than this will use streaming mode with check bytes
    // AEAD requires loading entire plaintext into memory, so we limit it
    // to avoid OutOfMemoryError on Android devices
    private static final long AEAD_MAX_FILE_SIZE = 50 * 1024 * 1024; // 50 MB
    
    // Flags stored in iteration count field (high bits)
    // Bit 31 (0x80000000): AEAD mode (ChaCha20-Poly1305) - for small files
    // Bit 30 (0x40000000): Argon2id KDF (instead of PBKDF2)
    // Bit 29 (0x20000000): SecretStream mode (libsodium XChaCha20-Poly1305) - for large files
    private static final int AEAD_FLAG = 0x80000000;
    private static final int ARGON2_FLAG = 0x40000000;
    private static final int STREAM_FLAG = 0x20000000;
    private static final int FLAGS_MASK = 0xE0000000;  // All three flags
    private static final int ITERATION_MASK = 0x1FFFFFFF;  // Iteration count (29 bits)
    
    // Argon2id parameters (OWASP recommendations for high-security)
    // These provide strong protection against GPU/ASIC attacks
    private static final int ARGON2_MEMORY_KB = 65536;  // 64 MB
    private static final int ARGON2_ITERATIONS = 3;      // Time cost
    private static final int ARGON2_PARALLELISM = 4;     // Parallel threads
    
    private static final int INTEGER_LENGTH = 4;
    public static final int DIR_HASH_LENGTH = 8;
    private static final String JSON_ORIGINAL_NAME = "originalName";
    private static final String JSON_FILE_TYPE = "fileType";
    private static final String JSON_CONTENT_TYPE = "contentType";
    private static final String JSON_RELATED_FILES = "relatedFiles";
    private static final String JSON_THUMB_NAME = "thumbName";
    private static final String JSON_NOTE_NAME = "noteName";
    public static final String BIOMETRICS_ALIAS = "vault_key";

    // Encryption versions
    public static final int ENCRYPTION_VERSION_5 = 5;  // V5: Composite file with internal sections (FILE + THUMBNAIL + NOTE)

    public static final String ENCRYPTED_PREFIX = ".valv.";
    public static final String ENCRYPTED_SUFFIX = ".valv";

    // Version 5: No extension - just random hash filename (no link to app)
    public static final String SUFFIX_V5 = "";  // No extension for V5

    // Content type enum for V3 files
    public enum ContentType {
        FILE(0),
        THUMBNAIL(1),
        NOTE(2);

        public final int value;

        ContentType(int value) {
            this.value = value;
        }

        public static ContentType fromValue(int value) {
            switch (value) {
                case 1:
                    return THUMBNAIL;
                case 2:
                    return NOTE;
                default:
                    return FILE;
            }
        }
    }

    // Class for storing related file information (thumbnail, note, etc.)
    public static class RelatedFile {
        public final String fileHash;      // SHA256 hash of (filename + encryption key)
        public final ContentType contentType; // THUMBNAIL, NOTE, etc.

        public RelatedFile(String fileHash, ContentType contentType) {
            this.fileHash = fileHash;
            this.contentType = contentType;
        }

        // Serialize to JSONObject for storing in metadata
        public JSONObject toJSON() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("hash", fileHash);
            json.put("contentType", contentType.value);
            return json;
        }

        // Deserialize from JSONObject
        public static RelatedFile fromJSON(JSONObject json) throws JSONException {
            String hash = json.getString("hash");
            int contentTypeValue = json.getInt("contentType");
            ContentType contentType = ContentType.fromValue(contentTypeValue);
            return new RelatedFile(hash, contentType);
        }
    }

    public static int getFileTypeFromMime(@Nullable String mimeType) {
        if (mimeType == null) {
            return FileType.TYPE_IMAGE;
        } else if (mimeType.equals("image/gif")) {
            return FileType.TYPE_GIF;
        } else if (mimeType.startsWith("image/")) {
            return FileType.TYPE_IMAGE;
        } else if (mimeType.startsWith("text/")) {
            return FileType.TYPE_TEXT;
        } else {
            return FileType.TYPE_VIDEO;
        }
    }

    /**
     * Calculate SHA256 hash for file correlation
     * Hash = SHA256(filename + encryption key as bytes)
     * This hash is used to correlate related files (thumbnails, notes) without using deterministic naming
     *
     * @param filename the encrypted filename (e.g., "abc123.valv")
     * @param secretKey the encryption key used for this file
     * @return SHA256 hash as hex string (64 chars)
     */
    public static String calculateFileHash(@NonNull String filename, @NonNull SecretKey secretKey) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        // Add filename bytes
        digest.update(filename.getBytes(StandardCharsets.UTF_8));
        
        // Add secret key bytes (encoded format)
        byte[] keyEncoded = secretKey.getEncoded();
        digest.update(keyEncoded);
        
        // Calculate hash
        byte[] hash = digest.digest();
        
        // Convert to hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static DocumentFile importFileToDirectory(FragmentActivity context, DocumentFile sourceFile, DocumentFile directory, char[] password, int version, @Nullable IOnProgress onProgress, AtomicBoolean interrupted) throws IOException {
        int fileType = getFileTypeFromMime(sourceFile.getType());
        return importFileToDirectory(context, sourceFile, directory, password, version, fileType, onProgress, interrupted);
    }

    public static DocumentFile importFileToDirectory(FragmentActivity context, DocumentFile sourceFile, DocumentFile directory, char[] password, int version, int fileType, @Nullable IOnProgress onProgress, AtomicBoolean interrupted) throws IOException {
        if (password == null || password.length == 0) {
            throw new RuntimeException("No password");
        }

        // V5: Use composite file with internal sections
        if (version >= ENCRYPTION_VERSION_5) {
            try {
                InputStream fileInputStream = context.getContentResolver().openInputStream(sourceFile.getUri());
                if (fileInputStream == null) {
                    SecureLog.e(TAG, "importFileToDirectory: could not open source file");
                    return null;
                }

                // Get file size
                long fileSize = sourceFile.length();

                // Generate thumbnail from source file
                // Note: Use applicationContext for Glide since this may run on background thread
                Bitmap thumbBitmap = null;
                try {
                    thumbBitmap = Glide.with(context.getApplicationContext())
                            .asBitmap()
                            .load(sourceFile.getUri())
                            .centerCrop()
                            .override(512, 512)  // Request a reasonable thumbnail size
                            .submit()
                            .get(30, TimeUnit.SECONDS);  // Add timeout
                    SecureLog.d(TAG, "importFileToDirectory: thumbnail generated successfully, size=" + 
                            (thumbBitmap != null ? thumbBitmap.getWidth() + "x" + thumbBitmap.getHeight() : "null"));
                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    SecureLog.w(TAG, "importFileToDirectory: could not generate thumbnail for " + sourceFile.getName(), e);
                }

                // Convert thumbnail to byte array (JPEG)
                byte[] thumbnailBytes = null;
                if (thumbBitmap != null) {
                    ByteArrayOutputStream thumbOut = new ByteArrayOutputStream();
                    thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 85, thumbOut);
                    thumbnailBytes = thumbOut.toByteArray();
                }

                // No note for file import (just metadata)
                DocumentFile result = createCompositeFile(
                        context,
                        fileInputStream,
                        fileSize,
                        thumbnailBytes != null ? new ByteArrayInputStream(thumbnailBytes) : null,
                        thumbnailBytes != null ? thumbnailBytes.length : 0,
                        null, // no note for file import
                        directory,
                        password,
                        sourceFile.getName(),
                        fileType);

                fileInputStream.close();
                if (thumbBitmap != null) {
                    thumbBitmap.recycle();
                }

                return result;

            } catch (GeneralSecurityException | IOException | JSONException e) {
                SecureLog.e(TAG, "importFileToDirectory: V5 composite file creation failed", e);
                return null;
            }
        }

        // V3-V4 no longer supported. Only V5 is supported for new files.
        throw new IOException("Only V5 encryption is supported. Please use version=5 when importing files.");
    }

    public static DocumentFile importNoteToDirectory(FragmentActivity context, String note, String fileNameWithoutPrefix, DocumentFile directory, char[] password, int version) throws IOException {
        return importNoteToDirectory(context, note, fileNameWithoutPrefix, directory, password, version, FileType.TYPE_TEXT);
    }

    public static DocumentFile importNoteToDirectory(FragmentActivity context, String note, String fileNameWithoutPrefix, DocumentFile directory, char[] password, int version, int fileType) throws IOException {
        if (password == null || password.length == 0) {
            throw new RuntimeException("No password");
        }

        // V3-V4 no longer supported. Only V5 is supported for new files.
        throw new IOException("Only V5 encryption is supported. Please use version=5 when importing files.");
    }

    public static DocumentFile importTextToDirectory(FragmentActivity context, String text, @Nullable String fileNameWithoutSuffix, DocumentFile directory, char[] password, int version) {
        return importTextToDirectory(context, text, fileNameWithoutSuffix, directory, password, version, FileType.TYPE_TEXT);
    }

    public static DocumentFile importTextToDirectory(FragmentActivity context, String text, @Nullable String fileNameWithoutSuffix, DocumentFile directory, char[] password, int version, int fileType) {
        if (password == null || password.length == 0) {
            throw new RuntimeException("No password");
        }

        if (fileNameWithoutSuffix == null) {
            fileNameWithoutSuffix = StringStuff.getRandomFileName();
        }
        
        // V5: Only V5 is supported for new text files
        String suffix = SUFFIX_V5;
        String fileName = fileNameWithoutSuffix + suffix;
        DocumentFile file = directory.createFile("", fileName);

        try {
            String sourceFileName = fileNameWithoutSuffix + FileType.TEXT_V5.extension;
            createTextFile(context, text, file, password, sourceFileName, version, fileType);
        } catch (GeneralSecurityException | IOException | JSONException e) {
            SecureLog.e(TAG, "importTextToDirectory: failed " + e.getMessage());
            e.printStackTrace();
            file.delete();
            return null;
        }

        return file;
    }

    public static class Streams {
        private final InputStream inputStream;
        private final CipherOutputStream outputStream;
        private final SecretKey secretKey;
        private final String originalFileName, inputString;
        private final int fileType;
        private final ContentType contentType;
        private final RelatedFile[] relatedFiles;
        public String thumbFileName;
        public CompositeStreams compositeStreams;

        private Streams(@NonNull InputStream inputStream, @NonNull CipherOutputStream outputStream, @NonNull SecretKey secretKey) {
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.secretKey = secretKey;
            this.originalFileName = "";
            this.inputString = null;
            this.fileType = -1;
            this.contentType = ContentType.FILE;
            this.relatedFiles = null;
        }

        private Streams(@NonNull String inputString, @NonNull CipherOutputStream outputStream, @NonNull SecretKey secretKey) {
            this.inputString = inputString;
            this.inputStream = null;
            this.outputStream = outputStream;
            this.secretKey = secretKey;
            this.originalFileName = "";
            this.fileType = -1;
            this.contentType = ContentType.FILE;
            this.relatedFiles = null;
        }

        private Streams(@NonNull InputStream inputStream, @NonNull SecretKey secretKey, @NonNull String originalFileName) {
            this.inputStream = inputStream;
            this.outputStream = null;
            this.secretKey = secretKey;
            this.originalFileName = originalFileName;
            this.inputString = null;
            this.fileType = -1;
            this.contentType = ContentType.FILE;
            this.relatedFiles = null;
        }

        private Streams(@NonNull InputStream inputStream, @NonNull SecretKey secretKey, @NonNull String originalFileName, int fileType) {
            this.inputStream = inputStream;
            this.outputStream = null;
            this.secretKey = secretKey;
            this.originalFileName = originalFileName;
            this.inputString = null;
            this.fileType = fileType;
            this.contentType = ContentType.FILE;
            this.relatedFiles = null;
        }

        private Streams(@NonNull InputStream inputStream, @NonNull SecretKey secretKey, @NonNull String originalFileName, int fileType, ContentType contentType) {
            this.inputStream = inputStream;
            this.outputStream = null;
            this.secretKey = secretKey;
            this.originalFileName = originalFileName;
            this.inputString = null;
            this.fileType = fileType;
            this.contentType = contentType;
            this.relatedFiles = null;
        }

        private Streams(@NonNull InputStream inputStream, @NonNull SecretKey secretKey, @NonNull String originalFileName, int fileType, ContentType contentType, RelatedFile[] relatedFiles) {
            this.inputStream = inputStream;
            this.outputStream = null;
            this.secretKey = secretKey;
            this.originalFileName = originalFileName;
            this.inputString = null;
            this.fileType = fileType;
            this.contentType = contentType;
            this.relatedFiles = relatedFiles;
        }

        public String getInputString() {
            return inputString;
        }

        @Nullable
        public InputStream getInputStream() {
            // V5: Return file section from composite streams (with full caching)
            if (compositeStreams != null) {
                try {
                    return compositeStreams.getFileSection();
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }
            // V1-V4: Return plain input stream
            return inputStream;
        }

        /**
         * Get the file stream for V5 files with streaming (no full cache).
         * Useful for large files like videos.
         * For V1-V4, same as getInputStream().
         * 
         * @return InputStream for file content
         */
        @Nullable
        public InputStream getInputStreamStreaming() {
            // V5: Return file section with streaming (on-demand reading)
            if (compositeStreams != null) {
                try {
                    return compositeStreams.getFileSectionStreaming();
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }
            // V1-V4: Return plain input stream
            return inputStream;
        }

        /**
         * Get the file content as a byte array.
         * For V5, reads the FILE section into memory.
         * For V1-V4, reads the entire inputStream into memory.
         * 
         * @return Byte array of file content, or null if reading fails
         */
        @Nullable
        public byte[] getFileBytes() {
            try {
                if (compositeStreams != null) {
                    // V5: Read FILE section
                    InputStream fileSection = compositeStreams.getFileSection();
                    if (fileSection != null) {
                        return readAllBytes(fileSection);
                    }
                } else {
                    // V1-V4: Read inputStream
                    if (inputStream != null) {
                        return readAllBytes(inputStream);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }

        /**
         * Helper method to read all bytes from an InputStream.
         */
        private byte[] readAllBytes(InputStream is) throws IOException {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[16384];
            try {
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
            } finally {
                // Security: Wipe temporary buffer
                java.util.Arrays.fill(data, (byte) 0);
            }
            return buffer.toByteArray();
        }

        @NonNull
        public String getOriginalFileName() {
            return originalFileName;
        }

        public int getFileType() {
            return fileType;
        }

        public ContentType getContentType() {
            return contentType;
        }

        @Nullable
        public RelatedFile[] getRelatedFiles() {
            return relatedFiles;
        }

        @Nullable
        public String getThumbFileName() {
            return thumbFileName;
        }

        public void close() {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                secretKey.destroy();
            } catch (DestroyFailedException e) {
                // Expected on Android - SecretKeySpec doesn't support destroy()
                // This is not a security issue, just a limitation of the platform
            }
        }
    }

    /**
     * Result class for V5 composite file metadata reading.
     * Contains thumbnail URI and file type information.
     */
    public static class V5MetadataResult {
        @Nullable
        public final Uri thumbUri;
        public final int fileType;
        @Nullable
        public final String originalName;

        public V5MetadataResult(@Nullable Uri thumbUri, int fileType, @Nullable String originalName) {
            this.thumbUri = thumbUri;
            this.fileType = fileType;
            this.originalName = originalName;
        }
    }

    private static void createFile(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, @Nullable IOnProgress onProgress, int version, AtomicBoolean interrupted) throws GeneralSecurityException, IOException, JSONException {
        createFile(context, input, outputFile, password, sourceFileName, onProgress, version, -1, interrupted);
    }

    private static void createFile(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, @Nullable IOnProgress onProgress, int version, int fileType, AtomicBoolean interrupted) throws GeneralSecurityException, IOException, JSONException {
        createFile(context, input, outputFile, password, sourceFileName, onProgress, version, fileType, interrupted, null);
    }

    private static void createFile(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, @Nullable IOnProgress onProgress, int version, int fileType, AtomicBoolean interrupted, @Nullable String thumbFileName) throws GeneralSecurityException, IOException, JSONException {
        Streams streams = getCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, thumbFileName);

        int read;
        byte[] buffer = new byte[2048];
        long progress = 0;
        try {
            while ((read = streams.inputStream.read(buffer)) != -1) {
                if (interrupted.get()) {
                    streams.close();
                    return;
                }
                streams.outputStream.write(buffer, 0, read);
                if (onProgress != null) {
                    progress += read;
                    onProgress.onProgress(progress);
                }
            }
        } finally {
            // Security: Wipe buffer containing plaintext data
            java.util.Arrays.fill(buffer, (byte) 0);
        }

        streams.close();
    }

    /**
     * V5: Create a composite encrypted file with internal sections.
     * All content (FILE, THUMBNAIL, NOTE) is stored in a single .valv file.
     *
     * @param context Android context
     * @param fileInputStream Input stream with file data
     * @param fileSize Size of file in bytes
     * @param thumbnailInputStream Optional thumbnail data (null if no thumbnail)
     * @param thumbnailSize Size of thumbnail in bytes (0 if no thumbnail)
     * @param noteBytes Optional note data (null if no note)
     * @param outputDirectory DocumentFile of output directory
     * @param password Encryption password
     * @param originalFileName Original filename for metadata
     * @param fileType File type enum value
     * @return Pair<success, hasThumb> - true if file created, true if thumbnail was included
     */
    private static DocumentFile createCompositeFile(
            FragmentActivity context,
            InputStream fileInputStream,
            long fileSize,
            @Nullable InputStream thumbnailInputStream,
            long thumbnailSize,
            @Nullable byte[] noteBytes,
            DocumentFile outputDirectory,
            char[] password,
            String originalFileName,
            int fileType) throws GeneralSecurityException, IOException, JSONException {

        if (password == null || password.length == 0) {
            throw new RuntimeException("No password");
        }

        // Generate random filename for V5 (no extension - just the hash)
        String fileName = StringStuff.getRandomFileName() + SUFFIX_V5;
        String tmpFileName = fileName + ".tmp";

        // Create temporary file first (atomic write pattern)
        DocumentFile tmpFile = outputDirectory.createFile("", tmpFileName);
        if (tmpFile == null) {
            SecureLog.e(TAG, "createCompositeFile: could not create temporary file");
            return null;
        }

        try {
            // Write composite file to temporary location
            writeCompositeFile(
                    context,
                    fileInputStream, fileSize,
                    thumbnailInputStream, thumbnailSize,
                    noteBytes,
                    tmpFile,
                    password,
                    originalFileName,
                    fileType);

            // Atomic rename: .tmp → .valv
            DocumentFile finalFile = outputDirectory.createFile("", fileName);
            if (finalFile == null) {
                SecureLog.e(TAG, "createCompositeFile: could not create final file");
                tmpFile.delete();
                return null;
            }

            // Copy content from tmp to final
            try (InputStream tmpIn = context.getContentResolver().openInputStream(tmpFile.getUri());
                 OutputStream finalOut = context.getContentResolver().openOutputStream(finalFile.getUri())) {
                if (tmpIn != null && finalOut != null) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    try {
                        while ((bytesRead = tmpIn.read(buffer)) != -1) {
                            finalOut.write(buffer, 0, bytesRead);
                        }
                    } finally {
                        // Security: Wipe buffer (encrypted data, but still good practice)
                        java.util.Arrays.fill(buffer, (byte) 0);
                    }
                    finalOut.flush();
                }
            }

            tmpFile.delete();

            return finalFile;

        } catch (Exception e) {
            SecureLog.e(TAG, "createCompositeFile: error writing composite file", e);
            tmpFile.delete();
            throw e;
        }
    }

    /**
     * Write composite file content (helper for createCompositeFile).
     * 
     * For files <= AEAD_MAX_FILE_SIZE: Uses ChaCha20-Poly1305 AEAD
     * For files > AEAD_MAX_FILE_SIZE: Uses streaming ChaCha20 with check bytes
     * 
     * Format V5 with AEAD (small files):
     * - Header: [version:4][salt:16][iv:12][iteration_count_with_flags:4]
     * - Body: [encrypted_data + poly1305_tag:16]
     * 
     * Format V5 with streaming (large files):
     * - Header: [version:4][salt:16][iv:12][iteration_count_with_flags:4][check_bytes:12]
     * - Body: [encrypted_data] (check_bytes also inside encrypted content for verification)
     */
    private static void writeCompositeFile(
            FragmentActivity context,
            InputStream fileInputStream,
            long fileSize,
            @Nullable InputStream thumbnailInputStream,
            long thumbnailSize,
            @Nullable byte[] noteBytes,
            DocumentFile outputFile,
            char[] password,
            String originalFileName,
            int fileType) throws GeneralSecurityException, IOException, JSONException {

        // Calculate total content size to decide encryption mode
        long totalContentSize = fileSize + thumbnailSize + (noteBytes != null ? noteBytes.length : 0);
        
        if (totalContentSize <= AEAD_MAX_FILE_SIZE) {
            // Use AEAD for smaller files (more secure, requires all data in memory)
            writeCompositeFileAEAD(context, fileInputStream, fileSize, thumbnailInputStream, 
                    thumbnailSize, noteBytes, outputFile, password, originalFileName, fileType);
        } else {
            // Use streaming for larger files (check bytes for verification, memory efficient)
            writeCompositeFileStreaming(context, fileInputStream, fileSize, thumbnailInputStream,
                    thumbnailSize, noteBytes, outputFile, password, originalFileName, fileType);
        }
    }
    
    /**
     * Write composite file using ChaCha20-Poly1305 AEAD (for smaller files).
     * All data is loaded into memory for authenticated encryption.
     */
    private static void writeCompositeFileAEAD(
            FragmentActivity context,
            InputStream fileInputStream,
            long fileSize,
            @Nullable InputStream thumbnailInputStream,
            long thumbnailSize,
            @Nullable byte[] noteBytes,
            DocumentFile outputFile,
            char[] password,
            String originalFileName,
            int fileType) throws GeneralSecurityException, IOException, JSONException {

        SecureRandom sr = SecureRandom.getInstanceStrong();
        Settings settings = Settings.getInstance(context);
        final int ITERATION_COUNT = settings.getIterationCount();
        final boolean useArgon2 = settings.useArgon2();
        
        // Set flags in iteration count:
        // - AEAD_FLAG: Set for AEAD mode (ChaCha20-Poly1305)
        // - ARGON2_FLAG: Set if using Argon2id instead of PBKDF2
        int storedIterationCount = ITERATION_COUNT | AEAD_FLAG;
        if (useArgon2) {
            storedIterationCount |= ARGON2_FLAG;
        }

        // Generate header components
        byte[] versionBytes = toByteArray(ENCRYPTION_VERSION_5);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] ivBytes = new byte[IV_LENGTH];
        byte[] iterationCountBytes = toByteArray(storedIterationCount);
        sr.nextBytes(salt);
        sr.nextBytes(ivBytes);

        // Derive key using the appropriate KDF
        SecretKey secretKey = deriveKey(password, salt, ITERATION_COUNT, useArgon2);

        // Build plaintext content
        ByteArrayOutputStream plaintextBuffer = new ByteArrayOutputStream();
        
        // Build metadata JSON
        JSONObject json = new JSONObject();
        json.put(JSON_ORIGINAL_NAME, originalFileName);
        if (fileType >= 0) {
            json.put(JSON_FILE_TYPE, fileType);
        }
        json.put(JSON_CONTENT_TYPE, ContentType.FILE.value);

        // Record which sections are present
        JSONObject sectionsObj = new JSONObject();
        sectionsObj.put("FILE", true);
        boolean hasThumbnail = thumbnailInputStream != null && thumbnailSize > 0;
        sectionsObj.put("THUMBNAIL", hasThumbnail);
        sectionsObj.put("NOTE", noteBytes != null && noteBytes.length > 0);
        json.put("sections", sectionsObj);
        
        SecureLog.d(TAG, "writeCompositeFileAEAD: hasThumbnail=" + hasThumbnail + ", thumbnailSize=" + thumbnailSize);

        // Write metadata with newline delimiters
        plaintextBuffer.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));

        // Write FILE section
        SectionWriter sectionWriter = new SectionWriter(plaintextBuffer);
        byte[] fileData = readAllBytes(fileInputStream);
        sectionWriter.writeFileSection(new ByteArrayInputStream(fileData), fileData.length);
        SecureLog.d(TAG, "writeCompositeFileAEAD: wrote FILE section, size=" + fileData.length);

        // Write THUMBNAIL section if present
        if (hasThumbnail) {
            byte[] thumbData = readAllBytes(thumbnailInputStream);
            sectionWriter.writeThumbnailSection(new ByteArrayInputStream(thumbData), thumbData.length);
            SecureLog.d(TAG, "writeCompositeFileAEAD: wrote THUMBNAIL section, size=" + thumbData.length);
        }

        // Write NOTE section if present
        if (noteBytes != null && noteBytes.length > 0) {
            sectionWriter.writeNoteSection(noteBytes);
        }

        // Write END marker
        sectionWriter.writeEndMarker();

        byte[] plaintext = plaintextBuffer.toByteArray();

        // Encrypt with ChaCha20-Poly1305 AEAD
        Cipher cipher = Cipher.getInstance(CIPHER_AEAD);
        AlgorithmParameterSpec ivSpec = new IvParameterSpec(ivBytes);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
        
        // Use header as AAD (Associated Authenticated Data)
        byte[] aad = new byte[4 + SALT_LENGTH + IV_LENGTH + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, SALT_LENGTH);
        System.arraycopy(ivBytes, 0, aad, 4 + SALT_LENGTH, IV_LENGTH);
        System.arraycopy(iterationCountBytes, 0, aad, 4 + SALT_LENGTH + IV_LENGTH, 4);
        cipher.updateAAD(aad);
        
        byte[] ciphertext = cipher.doFinal(plaintext);

        // Open output stream and write
        OutputStream fos = new BufferedOutputStream(
                context.getContentResolver().openOutputStream(outputFile.getUri()),
                1024 * 32);

        // Write header (36 bytes - no check bytes for AEAD)
        fos.write(versionBytes);
        fos.write(salt);
        fos.write(ivBytes);
        fos.write(iterationCountBytes);
        
        // Write encrypted content (includes Poly1305 tag at end)
        fos.write(ciphertext);
        
        fos.flush();
        fos.close();
        
        // Clean up sensitive data using SecureMemoryManager
        SecureMemoryManager.getInstance().wipeNow(ciphertext);
        try {
            secretKey.destroy();
        } catch (DestroyFailedException e) {
            // Ignore
        }
    }
    
    /**
     * Write composite file using libsodium secretstream (for larger files).
     * Uses XChaCha20-Poly1305 streaming AEAD for memory-efficient authenticated encryption.
     * 
     * Format:
     * - Header: [version:4][salt:16][unused_iv:12][iteration_count|STREAM_FLAG|ARGON2_FLAG:4]
     * - Body: [secretstream_header:24][encrypted_chunks...]
     * 
     * Each chunk is authenticated individually, allowing streaming decryption
     * with per-chunk integrity verification.
     * 
     * This method streams data directly without loading the entire file into memory.
     */
    private static void writeCompositeFileStreaming(
            FragmentActivity context,
            InputStream fileInputStream,
            long fileSize,
            @Nullable InputStream thumbnailInputStream,
            long thumbnailSize,
            @Nullable byte[] noteBytes,
            DocumentFile outputFile,
            char[] password,
            String originalFileName,
            int fileType) throws GeneralSecurityException, IOException, JSONException {

        SecureRandom sr = SecureRandom.getInstanceStrong();
        Settings settings = Settings.getInstance(context);
        final int ITERATION_COUNT = settings.getIterationCount();
        final boolean useArgon2 = settings.useArgon2();
        
        // Set STREAM_FLAG to indicate secretstream mode (not AEAD)
        // Also set ARGON2_FLAG if using Argon2id
        int storedIterationCount = ITERATION_COUNT | STREAM_FLAG;
        if (useArgon2) {
            storedIterationCount |= ARGON2_FLAG;
        }

        // Generate header components
        byte[] versionBytes = toByteArray(ENCRYPTION_VERSION_5);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] ivBytes = new byte[IV_LENGTH];  // Not used for secretstream, but kept for format compatibility
        byte[] iterationCountBytes = toByteArray(storedIterationCount);
        sr.nextBytes(salt);
        sr.nextBytes(ivBytes);  // Random bytes for padding

        // Derive key using the appropriate KDF
        SecretKey secretKey = deriveKey(password, salt, ITERATION_COUNT, useArgon2);
        byte[] keyBytes = secretKey.getEncoded();

        // Open output stream
        OutputStream fos = new BufferedOutputStream(
                context.getContentResolver().openOutputStream(outputFile.getUri()),
                1024 * 64);  // 64KB buffer

        // Write V5 header (36 bytes - no check bytes for secretstream)
        fos.write(versionBytes);
        fos.write(salt);
        fos.write(ivBytes);  // Padding for format compatibility
        fos.write(iterationCountBytes);

        // Create secretstream encrypting output stream
        // This will encrypt data in chunks as we write, without loading everything in memory
        SecretStreamHelper.SecretStreamOutputStream encryptedOut = 
                new SecretStreamHelper.SecretStreamOutputStream(keyBytes, fos);
        
        // Build metadata JSON
        JSONObject json = new JSONObject();
        json.put(JSON_ORIGINAL_NAME, originalFileName);
        if (fileType >= 0) {
            json.put(JSON_FILE_TYPE, fileType);
        }
        json.put(JSON_CONTENT_TYPE, ContentType.FILE.value);

        JSONObject sectionsObj = new JSONObject();
        sectionsObj.put("FILE", true);
        sectionsObj.put("THUMBNAIL", thumbnailInputStream != null && thumbnailSize > 0);
        sectionsObj.put("NOTE", noteBytes != null && noteBytes.length > 0);
        json.put("sections", sectionsObj);

        // Write metadata (small, goes into buffer)
        encryptedOut.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));

        // Write FILE section - streams directly without loading into memory
        SectionWriter sectionWriter = new SectionWriter(encryptedOut);
        sectionWriter.writeFileSectionStreaming(fileInputStream, fileSize);

        // Write THUMBNAIL section if present (usually small)
        if (thumbnailInputStream != null && thumbnailSize > 0) {
            sectionWriter.writeThumbnailSectionStreaming(thumbnailInputStream, thumbnailSize);
        }

        // Write NOTE section if present (usually small)
        if (noteBytes != null && noteBytes.length > 0) {
            sectionWriter.writeNoteSection(noteBytes);
        }

        // Write END marker
        sectionWriter.writeEndMarker();

        // Finish and close the encrypted stream (writes final TAG_FINAL chunk)
        encryptedOut.finish();
        encryptedOut.close();
        
        // Clean up using SecureMemoryManager
        SecureMemoryManager.getInstance().wipeNow(keyBytes);
        try {
            secretKey.destroy();
        } catch (DestroyFailedException e) {
            // Ignore
        }
    }
    
    /**
     * Helper to read all bytes from an InputStream.
     */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        try {
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
        } finally {
            // Security: Wipe temporary buffer
            java.util.Arrays.fill(data, (byte) 0);
        }
        return buffer.toByteArray();
    }

    private static void createTextFile(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version) throws GeneralSecurityException, IOException, JSONException {
        createTextFile(context, input, outputFile, password, sourceFileName, version, -1, ContentType.FILE, null);
    }

    private static void createTextFile(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType) throws GeneralSecurityException, IOException, JSONException {
        createTextFile(context, input, outputFile, password, sourceFileName, version, fileType, ContentType.FILE, null);
    }

    private static void createTextFile(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType) throws GeneralSecurityException, IOException, JSONException {
        createTextFile(context, input, outputFile, password, sourceFileName, version, fileType, contentType, null);
    }

    private static void createTextFile(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType, @Nullable RelatedFile[] relatedFiles) throws GeneralSecurityException, IOException, JSONException {
        Streams streams = getTextCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, contentType, relatedFiles);
        streams.outputStream.write(streams.inputString.getBytes(StandardCharsets.UTF_8));
        streams.close();
    }

    private static void createThumb(FragmentActivity context, Uri input, DocumentFile outputThumbFile, char[] password, String sourceFileName, int version) throws GeneralSecurityException, IOException, ExecutionException, InterruptedException, JSONException {
        createThumb(context, input, outputThumbFile, password, sourceFileName, version, -1, ContentType.THUMBNAIL, null);
    }

    private static void createThumb(FragmentActivity context, Uri input, DocumentFile outputThumbFile, char[] password, String sourceFileName, int version, int fileType) throws GeneralSecurityException, IOException, ExecutionException, InterruptedException, JSONException {
        createThumb(context, input, outputThumbFile, password, sourceFileName, version, fileType, ContentType.THUMBNAIL, null);
    }

    private static void createThumb(FragmentActivity context, Uri input, DocumentFile outputThumbFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType) throws GeneralSecurityException, IOException, ExecutionException, InterruptedException, JSONException {
        createThumb(context, input, outputThumbFile, password, sourceFileName, version, fileType, contentType, null);
    }

    private static void createThumb(FragmentActivity context, Uri input, DocumentFile outputThumbFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType, @Nullable RelatedFile[] relatedFiles) throws GeneralSecurityException, IOException, ExecutionException, InterruptedException, JSONException {
        Streams streams = getCipherOutputStream(context, input, outputThumbFile, password, sourceFileName, version, fileType, contentType, relatedFiles);

        // Use applicationContext for Glide since this may run on background thread
        Bitmap bitmap = Glide.with(context.getApplicationContext()).asBitmap().load(input).centerCrop().submit(512, 512).get();

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream);
        byte[] byteArray = stream.toByteArray();
        streams.outputStream.write(byteArray);
        bitmap.recycle();

        streams.close();
    }

    /**
     * Decrypt an encrypted file and return streams for reading.
     * Supports AEAD, SecretStream, and legacy V5 formats.
     * 
     * AEAD format (iteration_count has AEAD_FLAG set):
     * - Header: [version:4][salt:16][iv:12][iteration_count|AEAD_FLAG:4]
     * - Body: [encrypted_data + poly1305_tag:16]
     * 
     * SecretStream format (iteration_count has STREAM_FLAG set):
     * - Header: [version:4][salt:16][unused_iv:12][iteration_count|STREAM_FLAG:4]
     * - Body: [secretstream_header:24][encrypted_chunks...]
     * 
     * Legacy format (no AEAD_FLAG or STREAM_FLAG):
     * - Header: [version:4][salt:16][iv:12][iteration_count:4][check_bytes:12]
     * - Body: [encrypted_data with check_bytes inside]
     */
    public static Streams getCipherInputStream(@NonNull InputStream inputStream, char[] password, boolean isThumb, int version) throws IOException, GeneralSecurityException, InvalidPasswordException, JSONException {
        byte[] versionBytes = new byte[INTEGER_LENGTH];
        byte[] salt = new byte[SALT_LENGTH];
        byte[] ivBytes = new byte[IV_LENGTH];
        byte[] iterationCountBytes = new byte[INTEGER_LENGTH];

        // Read header common to all formats
        inputStream.read(versionBytes);
        inputStream.read(salt);
        inputStream.read(ivBytes);
        inputStream.read(iterationCountBytes);

        final int DETECTED_VERSION = fromByteArray(versionBytes);
        final int rawIterationCount = fromByteArray(iterationCountBytes);
        
        // Extract flags from iteration count
        final boolean useAEAD = (rawIterationCount & AEAD_FLAG) != 0;
        final boolean useStream = (rawIterationCount & STREAM_FLAG) != 0;
        final boolean useArgon2 = (rawIterationCount & ARGON2_FLAG) != 0;
        final int ITERATION_COUNT = rawIterationCount & ITERATION_MASK; // Clear all flags

        // Derive key using the appropriate KDF
        SecretKey secretKey = deriveKey(password, salt, ITERATION_COUNT, useArgon2);

        if (useStream) {
            // SecretStream mode: libsodium XChaCha20-Poly1305 streaming
            return decryptSecretStream(inputStream, secretKey, DETECTED_VERSION);
        } else if (useAEAD) {
            // AEAD mode: ChaCha20-Poly1305
            return decryptAEAD(inputStream, secretKey, versionBytes, salt, ivBytes, iterationCountBytes, DETECTED_VERSION);
        } else {
            // Legacy mode: ChaCha20 with check bytes
            return decryptLegacy(inputStream, secretKey, ivBytes, password, salt, ITERATION_COUNT, DETECTED_VERSION);
        }
    }
    
    /**
     * Decrypt using libsodium secretstream (XChaCha20-Poly1305).
     * Each chunk is authenticated individually, allowing streaming with integrity.
     * Uses SecretStreamInputStream for true streaming decryption without loading all data in memory.
     */
    private static Streams decryptSecretStream(
            InputStream inputStream,
            SecretKey secretKey,
            int detectedVersion) throws IOException, GeneralSecurityException, InvalidPasswordException, JSONException {
        
        byte[] keyBytes = secretKey.getEncoded();
        
        // Create streaming decryption - data is decrypted on-demand as it's read
        SecretStreamHelper.SecretStreamInputStream decryptedStream = 
            new SecretStreamHelper.SecretStreamInputStream(keyBytes, inputStream);
        
        // Parse decrypted content (same as AEAD)
        if (detectedVersion >= ENCRYPTION_VERSION_5) {
            // Skip leading newline
            int newline1 = decryptedStream.read();
            if (newline1 != 0x0A) {
                throw new IOException("Not valid V5 SecretStream file, expected 0x0A but got 0x" + String.format("%02X", newline1));
            }

            // Read JSON metadata
            byte[] jsonBytes = readUntilNewline(decryptedStream);
            String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String originalName = json.has(JSON_ORIGINAL_NAME) ? json.getString(JSON_ORIGINAL_NAME) : "";
            int fileType = json.has(JSON_FILE_TYPE) ? json.getInt(JSON_FILE_TYPE) : -1;

            // Create CompositeStreams wrapper for reading V5 sections
            CompositeStreams compositeStreams = new CompositeStreams(decryptedStream);

            // Store metadata in a custom Streams object for compatibility
            Streams streams = new Streams(decryptedStream, secretKey, originalName, fileType, ContentType.FILE, null);
            streams.compositeStreams = compositeStreams;
            return streams;
        }

        throw new IOException("Only V5 encrypted files are supported.");
    }
    
    /**
     * Decrypt using ChaCha20-Poly1305 AEAD.
     * Authentication failure (wrong password or tampering) throws InvalidPasswordException.
     */
    private static Streams decryptAEAD(
            InputStream inputStream,
            SecretKey secretKey,
            byte[] versionBytes,
            byte[] salt,
            byte[] ivBytes,
            byte[] iterationCountBytes,
            int detectedVersion) throws IOException, GeneralSecurityException, InvalidPasswordException, JSONException {
        
        // Read all remaining data (ciphertext + tag)
        byte[] ciphertext = readAllBytes(inputStream);
        
        // Build AAD from header
        byte[] aad = new byte[4 + SALT_LENGTH + IV_LENGTH + 4];
        System.arraycopy(versionBytes, 0, aad, 0, 4);
        System.arraycopy(salt, 0, aad, 4, SALT_LENGTH);
        System.arraycopy(ivBytes, 0, aad, 4 + SALT_LENGTH, IV_LENGTH);
        System.arraycopy(iterationCountBytes, 0, aad, 4 + SALT_LENGTH + IV_LENGTH, 4);
        
        // Decrypt with AEAD
        Cipher cipher = Cipher.getInstance(CIPHER_AEAD);
        AlgorithmParameterSpec ivSpec = new IvParameterSpec(ivBytes);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
        cipher.updateAAD(aad);
        
        byte[] plaintext;
        try {
            plaintext = cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            // Authentication failed - file was tampered with or doesn't belong to this session
            throw new InvalidPasswordException("File authentication failed - file may be corrupted or from different session");
        }
        
        // Parse decrypted content
        ByteArrayInputStream plaintextStream = new ByteArrayInputStream(plaintext);
        
        // V5: Parse sections
        if (detectedVersion >= ENCRYPTION_VERSION_5) {
            // Skip leading newline
            int newline1 = plaintextStream.read();
            if (newline1 != 0x0A) {
                throw new IOException("Not valid V5 AEAD file, expected 0x0A but got 0x" + String.format("%02X", newline1));
            }

            // Read JSON metadata
            byte[] jsonBytes = readUntilNewline(plaintextStream);
            String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String originalName = json.has(JSON_ORIGINAL_NAME) ? json.getString(JSON_ORIGINAL_NAME) : "";
            int fileType = json.has(JSON_FILE_TYPE) ? json.getInt(JSON_FILE_TYPE) : -1;

            // Create CompositeStreams wrapper for reading V5 sections
            CompositeStreams compositeStreams = new CompositeStreams(plaintextStream);

            // Store metadata in a custom Streams object for compatibility
            Streams streams = new Streams(plaintextStream, secretKey, originalName, fileType, ContentType.FILE, null);
            streams.compositeStreams = compositeStreams;
            return streams;
        }

        throw new IOException("Only V5 encrypted files are supported.");
    }
    
    /**
     * Decrypt using legacy ChaCha20 with check bytes.
     * For backwards compatibility with files encrypted before AEAD migration.
     */
    private static Streams decryptLegacy(
            InputStream inputStream,
            SecretKey secretKey,
            byte[] ivBytes,
            char[] password,
            byte[] salt,
            int iterationCount,
            int detectedVersion) throws IOException, GeneralSecurityException, InvalidPasswordException, JSONException {
        
        byte[] checkBytes1 = new byte[CHECK_LENGTH];
        byte[] checkBytes2 = new byte[CHECK_LENGTH];
        
        // Read check bytes from header
        inputStream.read(checkBytes1);

        IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);

        Cipher cipher = Cipher.getInstance(CIPHER_LEGACY);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);
        CipherInputStream cipherInputStream = new MyCipherInputStream(inputStream, cipher);

        cipherInputStream.read(checkBytes2);
        if (!Arrays.equals(checkBytes1, checkBytes2)) {
            throw new InvalidPasswordException("Invalid password");
        }

        // V5: Return CompositeStreams for V5 files
        if (detectedVersion >= ENCRYPTION_VERSION_5) {
            // V5 files have sections instead of plaintext metadata
            // Skip the newline after header
            int newline1 = cipherInputStream.read();
            if (newline1 != 0x0A) {
                throw new IOException("Not valid V5 file, expected 0x0A but got 0x" + String.format("%02X", newline1));
            }

            // Read JSON metadata
            byte[] jsonBytes = readUntilNewline(cipherInputStream);
            String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            String originalName = json.has(JSON_ORIGINAL_NAME) ? json.getString(JSON_ORIGINAL_NAME) : "";
            int fileType = json.has(JSON_FILE_TYPE) ? json.getInt(JSON_FILE_TYPE) : -1;

            // Create CompositeStreams wrapper for reading V5 sections
            CompositeStreams compositeStreams = new CompositeStreams(cipherInputStream);

            // Store metadata in a custom Streams object for compatibility
            Streams streams = new Streams(cipherInputStream, secretKey, originalName, fileType, ContentType.FILE, null);
            streams.compositeStreams = compositeStreams;
            return streams;
        }

        // V3-V4 no longer supported. Only V5 is supported.
        throw new IOException("Only V5 encrypted files are supported. Please re-encrypt your files.");
    }

    @NonNull
    private static byte[] readUntilNewline(@NonNull InputStream inputStream) throws IOException {
        ArrayList<Byte> bytes = new ArrayList<>();
        byte[] read = new byte[1];
        while ((inputStream.read(read)) > 0) {
            if (read[0] == 0x0A) { // newline \n character
                break;
            }
            bytes.add(read[0]);
        }
        byte[] arr = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            arr[i] = bytes.get(i);
        }
        return arr;
    }

    private static Streams getCipherOutputStream(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, int version) throws GeneralSecurityException, IOException, JSONException {
        return getCipherOutputStream(context, input, outputFile, password, sourceFileName, version, -1, ContentType.FILE);
    }

    private static Streams getCipherOutputStream(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType) throws GeneralSecurityException, IOException, JSONException {
        return getCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, ContentType.FILE);
    }

    private static Streams getCipherOutputStream(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType) throws GeneralSecurityException, IOException, JSONException {
        return getCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, contentType, null, null);
    }

    private static Streams getCipherOutputStream(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, @Nullable String thumbFileName) throws GeneralSecurityException, IOException, JSONException {
        return getCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, ContentType.FILE, null, thumbFileName);
    }

    private static Streams getCipherOutputStream(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType, @Nullable RelatedFile[] relatedFiles) throws GeneralSecurityException, IOException, JSONException {
        return getCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, contentType, relatedFiles, null);
    }

    private static Streams getCipherOutputStream(FragmentActivity context, Uri input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType, @Nullable RelatedFile[] relatedFiles, @Nullable String thumbFileName) throws GeneralSecurityException, IOException, JSONException {
        SecureRandom sr = SecureRandom.getInstanceStrong();
        Settings settings = Settings.getInstance(context);
        final int ITERATION_COUNT = settings.getIterationCount();
        byte[] versionBytes = toByteArray(version);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] ivBytes = new byte[IV_LENGTH];
        byte[] iterationCount = toByteArray(ITERATION_COUNT);
        byte[] checkBytes = new byte[CHECK_LENGTH];
        generateSecureRandom(sr, salt, ivBytes, checkBytes);

        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
        KeySpec keySpec = new PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey secretKey = secretKeyFactory.generateSecret(keySpec);
        IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);

        Cipher cipher = Cipher.getInstance(CIPHER_LEGACY);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec);

        InputStream inputStream = context.getContentResolver().openInputStream(input);
        OutputStream fos = new BufferedOutputStream(context.getContentResolver().openOutputStream(outputFile.getUri()), 1024 * 32);
        writeSaltAndIV(versionBytes, salt, ivBytes, iterationCount, checkBytes, fos);
        fos.flush();
        CipherOutputStream cipherOutputStream = new CipherOutputStream(fos, cipher);
        cipherOutputStream.write(checkBytes);
        JSONObject json = new JSONObject();
        json.put(JSON_ORIGINAL_NAME, sourceFileName);
        json.put(JSON_FILE_TYPE, fileType);
        json.put(JSON_CONTENT_TYPE, contentType.value);
        // Store related files with hashes for correlation
        if (relatedFiles != null && relatedFiles.length > 0) {
            JSONArray relatedFilesJson = new JSONArray();
            for (RelatedFile relatedFile : relatedFiles) {
                relatedFilesJson.put(relatedFile.toJSON());
            }
            json.put(JSON_RELATED_FILES, relatedFilesJson);
        }
        cipherOutputStream.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));
        return new Streams(inputStream, cipherOutputStream, secretKey);
    }

    private static Streams getTextCipherOutputStream(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version) throws GeneralSecurityException, IOException, JSONException {
        return getTextCipherOutputStream(context, input, outputFile, password, sourceFileName, version, -1, ContentType.FILE);
    }

    private static Streams getTextCipherOutputStream(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType) throws GeneralSecurityException, IOException, JSONException {
        return getTextCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, ContentType.FILE);
    }

    private static Streams getTextCipherOutputStream(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType) throws GeneralSecurityException, IOException, JSONException {
        return getTextCipherOutputStream(context, input, outputFile, password, sourceFileName, version, fileType, contentType, null);
    }

    // Overload with RelatedFile array for correlation
    private static Streams getTextCipherOutputStream(FragmentActivity context, String input, DocumentFile outputFile, char[] password, String sourceFileName, int version, int fileType, ContentType contentType, @Nullable RelatedFile[] relatedFiles) throws GeneralSecurityException, IOException, JSONException {
        SecureRandom sr = SecureRandom.getInstanceStrong();
        Settings settings = Settings.getInstance(context);
        final int ITERATION_COUNT = settings.getIterationCount();
        byte[] versionBytes = toByteArray(version);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] ivBytes = new byte[IV_LENGTH];
        byte[] iterationCount = toByteArray(ITERATION_COUNT);
        byte[] checkBytes = new byte[CHECK_LENGTH];
        generateSecureRandom(sr, salt, ivBytes, checkBytes);

        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
        KeySpec keySpec = new PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey secretKey = secretKeyFactory.generateSecret(keySpec);
        IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);

        Cipher cipher = Cipher.getInstance(CIPHER_LEGACY);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec);

        OutputStream fos = new BufferedOutputStream(context.getContentResolver().openOutputStream(outputFile.getUri()), 1024 * 32);
        writeSaltAndIV(versionBytes, salt, ivBytes, iterationCount, checkBytes, fos);
        fos.flush();
        CipherOutputStream cipherOutputStream = new CipherOutputStream(fos, cipher);
        cipherOutputStream.write(checkBytes);
        JSONObject json = new JSONObject();
        json.put(JSON_ORIGINAL_NAME, sourceFileName);
        if (fileType >= 0) {
            json.put(JSON_FILE_TYPE, fileType);
        }
        json.put(JSON_CONTENT_TYPE, contentType.value);
        // Store related files with hashes for correlation
        if (relatedFiles != null && relatedFiles.length > 0) {
            JSONArray relatedFilesJson = new JSONArray();
            for (RelatedFile relatedFile : relatedFiles) {
                relatedFilesJson.put(relatedFile.toJSON());
            }
            json.put(JSON_RELATED_FILES, relatedFilesJson);
        }
        cipherOutputStream.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));
        return new Streams(input, cipherOutputStream, secretKey);
    }

    public static byte[] toByteArray(int value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    public static int fromByteArray(byte[] bytes) {
        return bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    /**
     * Derive encryption key from password using the appropriate KDF.
     * 
     * @param password User password
     * @param salt Random salt (16 bytes)
     * @param iterationCount For PBKDF2: number of iterations. For Argon2id: ignored (uses fixed params)
     * @param useArgon2 If true, use Argon2id. If false, use PBKDF2-HMAC-SHA512.
     * @return SecretKey for encryption/decryption
     */
    private static SecretKey deriveKey(char[] password, byte[] salt, int iterationCount, boolean useArgon2) 
            throws GeneralSecurityException {
        byte[] keyBytes;
        
        if (useArgon2) {
            // Argon2id - memory-hard KDF resistant to GPU/ASIC attacks
            // Convert char[] to byte[] for Argon2
            byte[] passwordBytes = charArrayToBytes(password);
            try {
                Argon2 argon2 = new Argon2.Builder(Version.V13)
                        .type(Type.Argon2id)
                        .memoryCost(MemoryCost.KiB(ARGON2_MEMORY_KB))
                        .parallelism(ARGON2_PARALLELISM)
                        .iterations(ARGON2_ITERATIONS)
                        .hashLength(KEY_LENGTH / 8)  // 32 bytes
                        .build();
                
                Argon2.Result result = argon2.hash(passwordBytes, salt);
                keyBytes = result.getHash();
                
                // Register keyBytes for later cleanup
                SecureMemoryManager.getInstance().register(keyBytes);
            } catch (org.signal.argon2.Argon2Exception e) {
                throw new GeneralSecurityException("Argon2 key derivation failed", e);
            } finally {
                // Clear password bytes from memory using SecureMemoryManager
                SecureMemoryManager.getInstance().wipeNow(passwordBytes);
            }
        } else {
            // PBKDF2-HMAC-SHA512 - legacy/fallback KDF
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
            KeySpec keySpec = new PBEKeySpec(password, salt, iterationCount, KEY_LENGTH);
            SecretKey tempKey = secretKeyFactory.generateSecret(keySpec);
            keyBytes = tempKey.getEncoded();
        }
        
        return new SecretKeySpec(keyBytes, "ChaCha20");
    }
    
    /**
     * Convert char[] password to byte[] using UTF-8 encoding.
     * The returned array should be cleared after use.
     * The returned bytes are registered with SecureMemoryManager for cleanup.
     */
    private static byte[] charArrayToBytes(char[] chars) {
        CharBuffer charBuffer = CharBuffer.wrap(chars);
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        
        // Clear the buffer using SecureMemoryManager
        SecureMemoryManager.getInstance().wipeNow(byteBuffer);
        
        // Register the returned bytes for later cleanup
        SecureMemoryManager.getInstance().register(bytes);
        
        return bytes;
    }

    @NonNull
    public static String getOriginalFilename(@NonNull InputStream inputStream, char[] password, boolean isThumb, int version) {
        String name = "";
        try {
            Streams streams = getCipherInputStream(inputStream, password, isThumb, version);
            name = streams.getOriginalFileName();
            streams.close();
        } catch (IOException | GeneralSecurityException | InvalidPasswordException |
                 JSONException e) {
            e.printStackTrace();
        }
        return name;
    }

    public static void checkPassword(@NonNull Context context, @NonNull Uri fileUri, @NonNull char[] password, int version, boolean isThumb) throws IOException, GeneralSecurityException, InvalidPasswordException, JSONException {
        InputStream inputStream = null;
        Streams streams = null;
        try {
            inputStream = context.getContentResolver().openInputStream(fileUri);
            if (inputStream == null) {
                throw new FileNotFoundException("Could not open " + fileUri);
            }
            streams = getCipherInputStream(inputStream, password, isThumb, version);
        } finally {
            if (streams != null) {
                streams.close();
            } else if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void generateSecureRandom(SecureRandom sr, byte[] salt, byte[] ivBytes, @Nullable byte[] checkBytes) {
        sr.nextBytes(salt);
        sr.nextBytes(ivBytes);
        if (checkBytes != null) {
            sr.nextBytes(checkBytes);
        }
    }

    public static byte[] generateSecureSalt(int length) {
        byte[] salt = new byte[length];
        SecureRandom sr;
        try {
            sr = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        sr.nextBytes(salt);
        return salt;
    }

    private static void writeSaltAndIV(@Nullable byte[] version, byte[] salt, byte[] ivBytes, @Nullable byte[] iterationCount, @Nullable byte[] checkBytes, OutputStream fos) throws IOException {
        if (version != null) {
            fos.write(version);
        }
        fos.write(salt);
        fos.write(ivBytes);
        if (iterationCount != null) {
            fos.write(iterationCount);
        }
        if (checkBytes != null) {
            fos.write(checkBytes);
        }
    }

    public static String readEncryptedTextFromUri(@NonNull Uri encryptedInput, Context context, int version, char[] password) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(encryptedInput);
            Streams cis = getCipherInputStream(inputStream, password, false, version);

            // V5: Read from composite note section if available
            // Note: cis.compositeStreams is set by getCipherInputStream when it detects V5 from header
            InputStream sourceStream;
            if (cis.compositeStreams != null) {
                String noteText = cis.compositeStreams.getNoteSection();
                cis.close();
                inputStream.close();
                return noteText;
            } else {
                sourceStream = cis.inputStream;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(sourceStream, StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            int read;
            char[] buffer = new char[8192];
            while ((read = br.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }

            cis.close();
            inputStream.close();
            return sb.toString();
        } catch (GeneralSecurityException | InvalidPasswordException | JSONException |
                 IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Read thumbnail from V5 composite file and save to cache.
     * For V5 files, the thumbnail is stored inside the main file as a section.
     *
     * @param encryptedInput The V5 file URI
     * @param context Android context
     * @param password Encryption password
     * @return File Uri for cached thumbnail, or null if no thumbnail or error
     */
    @Nullable
    public static InputStream readCompositeThumbInputStream(@NonNull Uri encryptedInput, Context context, char[] password) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(encryptedInput);
            Streams cis = getCipherInputStream(inputStream, password, false, ENCRYPTION_VERSION_5);

            if (cis.compositeStreams != null) {
                InputStream thumbStream = cis.compositeStreams.getThumbnailInputStream();
                // Don't close cis/inputStream here - caller is responsible
                inputStream.close();
                return thumbStream;
            }

            cis.close();
            inputStream.close();
            return null;
        } catch (GeneralSecurityException | InvalidPasswordException | JSONException |
                 IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Read thumbnail from V5 composite file and save to cache.
     * For V5 files, the thumbnail is stored inside the main file.
     *
     * @param encryptedInput The V5 file URI
     * @param context Android context
     * @param password Encryption password
     * @return Uri to the cached thumbnail file, or null if no thumbnail or error
     */
    @Nullable
    public static Uri readCompositeThumbToCache(@NonNull Uri encryptedInput, Context context, char[] password) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(encryptedInput);
            Streams cis = getCipherInputStream(inputStream, password, false, ENCRYPTION_VERSION_5);

            if (cis.compositeStreams != null && cis.compositeStreams.hasThumbnailSection()) {
                // Create cache file for thumbnail
                File cacheDir = context.getCacheDir();
                cacheDir.mkdir();
                Path thumbFile = Files.createTempFile(cacheDir.toPath(), null, ".jpg");
                
                // Write thumbnail to cache
                try (InputStream thumbInputStream = cis.compositeStreams.getThumbnailInputStream();
                     OutputStream fos = new FileOutputStream(thumbFile.toFile())) {
                    if (thumbInputStream != null) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = thumbInputStream.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                }

                Uri thumbUri = Uri.fromFile(thumbFile.toFile());
                cis.close();
                inputStream.close();
                return thumbUri;
            }

            cis.close();
            inputStream.close();
            return null;
        } catch (GeneralSecurityException | InvalidPasswordException | JSONException |
                 IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Read metadata and thumbnail from V5 composite file.
     * Returns both the thumbnail URI and the actual file type.
     *
     * @param encryptedInput The V5 file URI
     * @param context Android context
     * @param password Encryption password
     * @return V5MetadataResult with thumbUri, fileType, and originalName; or null on error
     */
    @Nullable
    public static V5MetadataResult readCompositeMetadata(@NonNull Uri encryptedInput, Context context, char[] password) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(encryptedInput);
            Streams cis = getCipherInputStream(inputStream, password, false, ENCRYPTION_VERSION_5);

            SecureLog.d(TAG, "readCompositeMetadata: compositeStreams=" + (cis.compositeStreams != null));
            
            Uri thumbUri = null;
            if (cis.compositeStreams != null) {
                boolean hasThumbnail = cis.compositeStreams.hasThumbnailSection();
                SecureLog.d(TAG, "readCompositeMetadata: hasThumbnailSection=" + hasThumbnail);
                
                if (hasThumbnail) {
                    // Create cache file for thumbnail
                    File cacheDir = context.getCacheDir();
                    if (!cacheDir.exists()) {
                        boolean dirCreated = cacheDir.mkdir();
                        if (!dirCreated && !cacheDir.exists()) {
                            SecureLog.e(TAG, "readCompositeMetadata: Failed to create cache directory: " + cacheDir.getAbsolutePath());
                            return null;
                        }
                    }
                    Path thumbFile = Files.createTempFile(cacheDir.toPath(), null, ".jpg");
                    
                    // Write thumbnail to cache
                    try (InputStream thumbInputStream = cis.compositeStreams.getThumbnailInputStream();
                         OutputStream fos = new FileOutputStream(thumbFile.toFile())) {
                        if (thumbInputStream != null) {
                            byte[] buffer = new byte[8192];
                            int read;
                            int totalBytes = 0;
                            while ((read = thumbInputStream.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                                totalBytes += read;
                            }
                            SecureLog.d(TAG, "readCompositeMetadata: wrote " + totalBytes + " bytes to thumb cache");
                        }
                    }
                    thumbUri = Uri.fromFile(thumbFile.toFile());
                }
            }

            int fileType = cis.getFileType();
            String originalName = cis.getOriginalFileName();
            cis.close();
            inputStream.close();
            
            return new V5MetadataResult(thumbUri, fileType, originalName);
        } catch (GeneralSecurityException | InvalidPasswordException | JSONException |
                 IOException e) {
            SecureLog.e(TAG, "readCompositeMetadata: error", e);
            e.printStackTrace();
            return null;
        }
    }

    public static void decryptToCache(FragmentActivity context, Uri encryptedInput, @Nullable String extension, int version, char[] password, IOnUriResult onUriResult) {
        new Thread(() -> {
            try {
                InputStream inputStream = new BufferedInputStream(context.getContentResolver().openInputStream(encryptedInput), 1024 * 32);

                File cacheDir = context.getCacheDir();
                cacheDir.mkdir();
                Path file = Files.createTempFile(null, extension);
                Uri fileUri = Uri.fromFile(file.toFile());
                OutputStream fos = context.getContentResolver().openOutputStream(fileUri);
                Streams cis = getCipherInputStream(inputStream, password, false, version);

                // V5: Use composite streams to read the FILE section
                // Note: cis.compositeStreams is set by getCipherInputStream when it detects V5 from header
                InputStream sourceStream;
                if (cis.compositeStreams != null) {
                    sourceStream = cis.compositeStreams.getFileSection();
                } else {
                    sourceStream = cis.inputStream;
                }

                int read;
                byte[] buffer = new byte[2048];
                while ((read = sourceStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.close();
                cis.close();
                inputStream.close();

                context.runOnUiThread(() -> onUriResult.onUriResult(fileUri));
            } catch (GeneralSecurityException | IOException | JSONException e) {
                e.printStackTrace();
                context.runOnUiThread(() -> onUriResult.onError(e));
            } catch (InvalidPasswordException e) {
                SecureLog.e(TAG, "decryptToCache: catch invalid password");
                e.printStackTrace();
                context.runOnUiThread(() -> onUriResult.onInvalidPassword(e));
            }
        }).start();
    }

    public static void decryptAndExport(FragmentActivity context, Uri encryptedInput, DocumentFile directory, GalleryFile galleryFile, boolean isVideo, int version, char[] password, IOnUriResult onUriResult) {
        DocumentFile documentFile = directory != null ? directory : DocumentFile.fromTreeUri(context, encryptedInput);
        String originalFileName = galleryFile.getOriginalName();
        if (originalFileName == null) {
            try {
                originalFileName = Encryption.getOriginalFilename(context.getContentResolver().openInputStream(encryptedInput), password, false, version);
                galleryFile.setOriginalName(originalFileName);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                SecureLog.e(TAG, "decryptAndExport: failed to decrypt original name");
            }
        }
        DocumentFile file = documentFile.createFile(isVideo ? "video/*" : "image/*", originalFileName != null ? originalFileName : (System.currentTimeMillis() + "_" + FileStuff.getFilenameFromUri(encryptedInput, true)));
        try {
            InputStream inputStream = new BufferedInputStream(context.getContentResolver().openInputStream(encryptedInput), 1024 * 32);

            OutputStream fos = context.getContentResolver().openOutputStream(file.getUri());
            Streams cis = getCipherInputStream(inputStream, password, false, version);

            // V5: Use composite streams to read the FILE section
            // Note: cis.compositeStreams is set by getCipherInputStream when it detects V5 from header
            InputStream sourceStream;
            if (cis.compositeStreams != null) {
                sourceStream = cis.compositeStreams.getFileSection();
            } else {
                sourceStream = cis.inputStream;
            }

            int read;
            byte[] buffer = new byte[2048];
            while ((read = sourceStream.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.close();
            cis.close();
            inputStream.close();

            onUriResult.onUriResult(file.getUri());
        } catch (GeneralSecurityException | IOException | JSONException e) {
            e.printStackTrace();
            onUriResult.onError(e);
        } catch (InvalidPasswordException e) {
            e.printStackTrace();
            onUriResult.onInvalidPassword(e);
        }
    }

    public static DirHash getDirHash(byte[] salt, char[] password) {
        try {
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
            KeySpec keySpec = new PBEKeySpec(password, salt, 120_000, KEY_LENGTH + 8);
            SecretKey secretKey = secretKeyFactory.generateSecret(keySpec);

            byte[] encoded = secretKey.getEncoded();
            byte[] hash = Arrays.copyOfRange(encoded, encoded.length - 8, encoded.length); // get last 8 bytes

            try {
                secretKey.destroy();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            Arrays.fill(encoded, (byte) 0);

            return new DirHash(salt, hash);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isAnimatedWebp(@NonNull InputStream inputStream) {
        if (!inputStream.markSupported()) {
            return false;
        }
        try {
            inputStream.mark(30);
            byte[] buffer = new byte[30];

            int totalBytesRead = 0;
            int bytesRead;
            while(totalBytesRead < 30 && (bytesRead = inputStream.read(buffer, totalBytesRead, 30 - totalBytesRead)) != -1) {
                totalBytesRead += bytesRead;
            }

            inputStream.reset();

            if (totalBytesRead < 30) {
                return false;
            }

            // Check for RIFF header
            if (buffer[0] != 'R' || buffer[1] != 'I' || buffer[2] != 'F' || buffer[3] != 'F') {
                return false;
            }

            // Check for WEBP format
            if (buffer[8] != 'W' || buffer[9] != 'E' || buffer[10] != 'B' || buffer[11] != 'P') {
                return false;
            }

            // Check for VP8X chunk
            if (buffer[12] == 'V' && buffer[13] == 'P' && buffer[14] == '8' && buffer[15] == 'X') {
                // The animation bit is the second bit of the feature byte (at offset 20)
                return (buffer[20] & 0x02) != 0;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public interface IOnUriResult {
        void onUriResult(Uri outputUri);

        void onError(Exception e);

        void onInvalidPassword(InvalidPasswordException e);
    }

    public static SecretKey getOrGenerateBiometricSecretKey() throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException, KeyStoreException, CertificateException, IOException, UnrecoverableKeyException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(BIOMETRICS_ALIAS, null);
        if (key != null) {
            return key;
        }

        KeyGenParameterSpec parameterSpec = new KeyGenParameterSpec.Builder(
                BIOMETRICS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                // Invalidate the keys if the user has registered a new biometric
                // credential, such as a new fingerprint. Can call this method only
                // on Android 7.0 (API level 24) or higher. The variable
                // "invalidatedByBiometricEnrollment" is true by default.
                .setInvalidatedByBiometricEnrollment(true)
                .build();

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGenerator.init(parameterSpec);
        return keyGenerator.generateKey();
    }

    public static void deleteBiometricSecretKey() throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(BIOMETRICS_ALIAS);
    }

    public static Cipher getBiometricCipher() throws NoSuchPaddingException, NoSuchAlgorithmException {
        return Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_CBC + "/"
                + KeyProperties.ENCRYPTION_PADDING_PKCS7);
    }

    public static byte[] toBytes(char[] chars) {
        CharBuffer charBuffer = CharBuffer.wrap(chars);
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
        byte[] bytes = Arrays.copyOfRange(byteBuffer.array(), byteBuffer.position(), byteBuffer.limit());
        Arrays.fill(byteBuffer.array(), (byte) 0); // clear sensitive data
        return bytes;
    }

    public static char[] toChars(byte[] bytes) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        CharBuffer charBuffer = StandardCharsets.UTF_8.decode(byteBuffer);
        char[] chars = Arrays.copyOfRange(charBuffer.array(), charBuffer.position(), charBuffer.limit());
        Arrays.fill(byteBuffer.array(), (byte) 0); // clear sensitive data
        return chars;
    }

    // ================================
    // Encrypted Folder Name Support
    // ================================
    
    // Maximum length for original folder names (in characters)
    // This ensures the encrypted base64url result fits within filesystem limits
    public static final int MAX_FOLDER_NAME_LENGTH = 30;
    
    /**
     * Create an encrypted folder name from an original folder name.
     * 
     * The encrypted format is: base64url([salt:16][iv:12][ciphertext:N][tag:16])
     * - Uses ChaCha20-Poly1305 AEAD for authenticated encryption
     * - Uses Argon2id KDF for key derivation (same as V5 files)
     * - No prefix or suffix - just raw base64url for privacy
     * 
     * @param originalName The original folder name (max 30 characters)
     * @param password The user's password
     * @return The encrypted folder name as base64url string, or null on error
     * @throws IllegalArgumentException if originalName exceeds MAX_FOLDER_NAME_LENGTH
     */
    @Nullable
    public static String createEncryptedFolderName(@NonNull String originalName, @NonNull char[] password) {
        if (password == null || password.length == 0) {
            return null;
        }

        if (originalName.length() > MAX_FOLDER_NAME_LENGTH) {
            throw new IllegalArgumentException("Folder name exceeds maximum length of " + MAX_FOLDER_NAME_LENGTH);
        }
        
        try {
            // Generate random salt and IV
            SecureRandom secureRandom = new SecureRandom();
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(salt);
            secureRandom.nextBytes(iv);
            
            // Derive key using Argon2id
            SecretKey key = deriveKey(password, salt, ARGON2_ITERATIONS, true);
            if (key == null) {
                SecureLog.e(TAG, "createEncryptedFolderName: Failed to derive key");
                return null;
            }
            
            try {
                // Encrypt using ChaCha20-Poly1305 AEAD
                Cipher cipher = Cipher.getInstance(CIPHER_AEAD);
                AlgorithmParameterSpec ivSpec = new IvParameterSpec(iv);
                cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
                
                byte[] plaintext = originalName.getBytes(StandardCharsets.UTF_8);
                byte[] ciphertext = cipher.doFinal(plaintext);
                // ciphertext includes the Poly1305 tag (16 bytes appended)
                
                // Combine: salt + iv + ciphertext (with tag)
                byte[] combined = new byte[SALT_LENGTH + IV_LENGTH + ciphertext.length];
                System.arraycopy(salt, 0, combined, 0, SALT_LENGTH);
                System.arraycopy(iv, 0, combined, SALT_LENGTH, IV_LENGTH);
                System.arraycopy(ciphertext, 0, combined, SALT_LENGTH + IV_LENGTH, ciphertext.length);
                
                // Encode as base64url (no padding)
                String encoded = android.util.Base64.encodeToString(combined, 
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                
                // Clear sensitive data
                Arrays.fill(plaintext, (byte) 0);
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(iv, (byte) 0);
                
                return encoded;
            } finally {
                // Securely destroy the key
                try {
                    key.destroy();
                } catch (DestroyFailedException ignored) {
                    // Key may not support destruction
                }
            }
        } catch (Exception e) {
            SecureLog.e(TAG, "createEncryptedFolderName: Encryption failed", e);
            return null;
        }
    }
    
    /**
     * Decrypt an encrypted folder name.
     * Note: This method does not use caching. Callers should use 
     * FileStuff.tryGetDecryptedFolderName() which handles caching.
     * 
     * @param encryptedName The base64url-encoded encrypted folder name
     * @param password The user's password
     * @return The original folder name, or null if decryption fails
     */
    @Nullable
    public static String decryptFolderName(@NonNull String encryptedName, @NonNull char[] password) {
        try {
            // Decode base64url
            byte[] combined = android.util.Base64.decode(encryptedName, 
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
            
            // Minimum size: salt (16) + iv (12) + tag (16) + at least 1 byte ciphertext
            int minSize = SALT_LENGTH + IV_LENGTH + POLY1305_TAG_LENGTH + 1;
            // Maximum size: salt (16) + iv (12) + tag (16) + max folder name (30 bytes)
            int maxSize = SALT_LENGTH + IV_LENGTH + POLY1305_TAG_LENGTH + MAX_FOLDER_NAME_LENGTH;
            
            if (combined.length < minSize || combined.length > maxSize) {
                return null;
            }
            
            // Extract components
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - SALT_LENGTH - IV_LENGTH];
            
            System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH, iv, 0, IV_LENGTH);
            System.arraycopy(combined, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertext.length);
            
            // Derive key using Argon2id
            SecretKey key = deriveKey(password, salt, ARGON2_ITERATIONS, true);
            if (key == null) {
                return null;
            }
            
            try {
                // Decrypt using ChaCha20-Poly1305 AEAD
                Cipher cipher = Cipher.getInstance(CIPHER_AEAD);
                AlgorithmParameterSpec ivSpec = new IvParameterSpec(iv);
                cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
                
                byte[] plaintext = cipher.doFinal(ciphertext);
                String originalName = new String(plaintext, StandardCharsets.UTF_8);
                
                // Clear sensitive data
                Arrays.fill(plaintext, (byte) 0);
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(iv, (byte) 0);
                
                return originalName;
            } finally {
                try {
                    key.destroy();
                } catch (DestroyFailedException ignored) {
                }
            }
        } catch (AEADBadTagException e) {
            // Authentication failed - not an encrypted folder or wrong password
            return null;
        } catch (IllegalArgumentException e) {
            // Invalid base64 - not an encrypted folder name
            return null;
        } catch (Exception e) {
            SecureLog.e(TAG, "decryptFolderName: Decryption failed", e);
            return null;
        }
    }
    
    /**
     * Check if a folder name could be an encrypted folder name.
     * This is a quick heuristic check before attempting full decryption.
     * 
     * @param folderName The folder name to check
     * @return true if the name looks like an encrypted folder name
     */
    public static boolean looksLikeEncryptedFolder(@NonNull String folderName) {
        // Encrypted folder names are base64url encoded
        // Minimum length: (16 + 12 + 16 + 1) bytes = 45 bytes -> ~60 chars in base64
        // They should only contain base64url characters: A-Z, a-z, 0-9, -, _
        if (folderName.length() < 59) {
            return false;
        }
        
        // Check for valid base64url characters only
        for (int i = 0; i < folderName.length(); i++) {
            char c = folderName.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || 
                  (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }
        
        return true;
    }
}
