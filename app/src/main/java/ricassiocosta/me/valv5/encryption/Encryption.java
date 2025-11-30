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
import android.util.Log;
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
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.security.auth.DestroyFailedException;

import ricassiocosta.me.valv5.data.DirHash;
import ricassiocosta.me.valv5.data.FileType;
import ricassiocosta.me.valv5.data.GalleryFile;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.interfaces.IOnProgress;
import ricassiocosta.me.valv5.utils.FileStuff;
import ricassiocosta.me.valv5.utils.Settings;
import ricassiocosta.me.valv5.utils.StringStuff;

public class Encryption {
    private static final String TAG = "Encryption";
    private static final String CIPHER = "ChaCha20/NONE/NoPadding";
    private static final String KEY_ALGORITHM = "PBKDF2withHmacSHA512";
    private static final int KEY_LENGTH = 256;
    public static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int CHECK_LENGTH = 12;
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
                    Log.e(TAG, "importFileToDirectory: could not open source file");
                    return null;
                }

                // Get file size
                long fileSize = sourceFile.length();

                // Generate thumbnail asynchronously and get bitmap
                Bitmap thumbBitmap = null;
                try {
                    thumbBitmap = Glide.with(context)
                            .asBitmap()
                            .load(sourceFile.getUri())
                            .submit()
                            .get();
                } catch (ExecutionException | InterruptedException e) {
                    Log.w(TAG, "importFileToDirectory: could not generate thumbnail", e);
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
                Log.e(TAG, "importFileToDirectory: V5 composite file creation failed", e);
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
            Log.e(TAG, "importTextToDirectory: failed " + e.getMessage());
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
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
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
                e.printStackTrace();
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
            Log.e(TAG, "createCompositeFile: could not create temporary file");
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
                Log.e(TAG, "createCompositeFile: could not create final file");
                tmpFile.delete();
                return null;
            }

            // Copy content from tmp to final
            try (InputStream tmpIn = context.getContentResolver().openInputStream(tmpFile.getUri());
                 OutputStream finalOut = context.getContentResolver().openOutputStream(finalFile.getUri())) {
                if (tmpIn != null && finalOut != null) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = tmpIn.read(buffer)) != -1) {
                        finalOut.write(buffer, 0, bytesRead);
                    }
                    finalOut.flush();
                }
            }

            tmpFile.delete();

            return finalFile;

        } catch (Exception e) {
            Log.e(TAG, "createCompositeFile: error writing composite file", e);
            tmpFile.delete();
            throw e;
        }
    }

    /**
     * Write composite file content (helper for createCompositeFile).
     * Writes: plaintext header → encrypted(CHECK + JSON + FILE section + THUMBNAIL section + NOTE section + END marker)
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

        SecureRandom sr = SecureRandom.getInstanceStrong();
        Settings settings = Settings.getInstance(context);
        final int ITERATION_COUNT = settings.getIterationCount();

        // Generate header components
        byte[] versionBytes = toByteArray(ENCRYPTION_VERSION_5);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] ivBytes = new byte[IV_LENGTH];
        byte[] iterationCount = toByteArray(ITERATION_COUNT);
        byte[] checkBytes = new byte[CHECK_LENGTH];
        generateSecureRandom(sr, salt, ivBytes, checkBytes);

        // Derive key
        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
        KeySpec keySpec = new PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey secretKey = secretKeyFactory.generateSecret(keySpec);
        IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);

        // Initialize cipher
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec);

        // Open output stream
        OutputStream fos = new BufferedOutputStream(
                context.getContentResolver().openOutputStream(outputFile.getUri()),
                1024 * 32);

        // Write plaintext header (48 bytes)
        writeSaltAndIV(versionBytes, salt, ivBytes, iterationCount, checkBytes, fos);
        fos.flush();

        // Create cipher output stream for encrypted content
        CipherOutputStream cipherOutputStream = new CipherOutputStream(fos, cipher);
        SectionWriter sectionWriter = new SectionWriter(cipherOutputStream);

        // Write encrypted CHECK bytes (for password verification)
        cipherOutputStream.write(checkBytes);

        // Build and write metadata JSON
        JSONObject json = new JSONObject();
        json.put(JSON_ORIGINAL_NAME, originalFileName);
        if (fileType >= 0) {
            json.put(JSON_FILE_TYPE, fileType);
        }
        json.put(JSON_CONTENT_TYPE, ContentType.FILE.value);

        // Record which sections are present
        JSONObject sectionsObj = new JSONObject();
        sectionsObj.put("FILE", true);
        sectionsObj.put("THUMBNAIL", thumbnailInputStream != null && thumbnailSize > 0);
        sectionsObj.put("NOTE", noteBytes != null && noteBytes.length > 0);
        json.put("sections", sectionsObj);

        // Write metadata with newline delimiters
        cipherOutputStream.write(("\n" + json + "\n").getBytes(StandardCharsets.UTF_8));

        // Write FILE section
        sectionWriter.writeFileSection(fileInputStream, fileSize);

        // Write THUMBNAIL section if present
        if (thumbnailInputStream != null && thumbnailSize > 0) {
            sectionWriter.writeThumbnailSection(thumbnailInputStream, thumbnailSize);
        }

        // Write NOTE section if present
        if (noteBytes != null && noteBytes.length > 0) {
            sectionWriter.writeNoteSection(noteBytes);
        }

        // Write END marker
        sectionWriter.writeEndMarker();

        // Close streams
        cipherOutputStream.close();
        fos.close();
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

        Bitmap bitmap = Glide.with(context).asBitmap().load(input).centerCrop().submit(512, 512).get();

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream);
        byte[] byteArray = stream.toByteArray();
        streams.outputStream.write(byteArray);
        bitmap.recycle();

        streams.close();
    }

    public static Streams getCipherInputStream(@NonNull InputStream inputStream, char[] password, boolean isThumb, int version) throws IOException, GeneralSecurityException, InvalidPasswordException, JSONException {
        byte[] versionBytes = new byte[INTEGER_LENGTH];
        byte[] salt = new byte[SALT_LENGTH];
        byte[] ivBytes = new byte[IV_LENGTH];
        byte[] iterationCount = new byte[INTEGER_LENGTH];
        byte[] checkBytes1 = new byte[CHECK_LENGTH];
        byte[] checkBytes2 = new byte[CHECK_LENGTH];

        //1. VERSION SALT IVBYTES ITERATIONCOUNT CHECKBYTES CHECKBYTES_ENC\n
        //2. {originalName, fileType, ...}\n
        //3. file data
        inputStream.read(versionBytes);
        inputStream.read(salt);
        inputStream.read(ivBytes);
        inputStream.read(iterationCount);
        inputStream.read(checkBytes1);

        final int DETECTED_VERSION = fromByteArray(versionBytes);
        final int ITERATION_COUNT = fromByteArray(iterationCount);

        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
        KeySpec keySpec = new PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey secretKey = secretKeyFactory.generateSecret(keySpec);
        IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);

        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);
        CipherInputStream cipherInputStream = new MyCipherInputStream(inputStream, cipher);

        cipherInputStream.read(checkBytes2);
        if (!Arrays.equals(checkBytes1, checkBytes2)) {
            throw new InvalidPasswordException("Invalid password");
        }

        // V5: Return CompositeStreams for V5 files
        if (DETECTED_VERSION >= ENCRYPTION_VERSION_5) {
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

        Cipher cipher = Cipher.getInstance(CIPHER);
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

        Cipher cipher = Cipher.getInstance(CIPHER);
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

            Uri thumbUri = null;
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
                thumbUri = Uri.fromFile(thumbFile.toFile());
            }

            int fileType = cis.getFileType();
            String originalName = cis.getOriginalFileName();
            
            cis.close();
            inputStream.close();
            
            return new V5MetadataResult(thumbUri, fileType, originalName);
        } catch (GeneralSecurityException | InvalidPasswordException | JSONException |
                 IOException e) {
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
                Log.e(TAG, "decryptToCache: catch invalid password");
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
                Log.e(TAG, "decryptAndExport: failed to decrypt original name");
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
}
