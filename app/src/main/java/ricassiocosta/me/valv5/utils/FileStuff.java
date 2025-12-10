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

package ricassiocosta.me.valv5.utils;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

import ricassiocosta.me.valv5.data.CursorFile;
import ricassiocosta.me.valv5.data.GalleryFile;
import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.encryption.FolderNameCache;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.security.SecureLog;

import static ricassiocosta.me.valv5.encryption.Encryption.SUFFIX_V5;

public class FileStuff {
    private static final String TAG = "FileStuff";

    @NonNull
    public static List<GalleryFile> getFilesInFolder(Context context, Uri pickedDir, boolean checkDecryptable) {
        Uri realUri = DocumentsContract.buildChildDocumentsUriUsingTree(pickedDir, DocumentsContract.getDocumentId(pickedDir));
        List<CursorFile> files = new ArrayList<>();
        Cursor c = context.getContentResolver().query(
                realUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE},
                null,
                null,
                null);
        if (c == null || !c.moveToFirst()) {
            if (c != null) {
                c.close();
            }
            return new ArrayList<>();
        }
        do {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(realUri, c.getString(0));
            String name = c.getString(1);
            long lastModified = c.getLong(2);
            String mimeType = c.getString(3);
            long size = c.getLong(4);
            files.add(new CursorFile(name, uri, lastModified, mimeType, size));
        } while (c.moveToNext());
        c.close();
        Collections.sort(files);
        List<GalleryFile> encryptedFilesInFolder = getEncryptedFilesInFolder(files, context);
        Collections.sort(encryptedFilesInFolder);

        return encryptedFilesInFolder;
    }

    @NonNull
    private static List<GalleryFile> getEncryptedFilesInFolder(@NonNull List<CursorFile> files, Context context) {
        List<CursorFile> documentFiles = new ArrayList<>();
        List<CursorFile> documentThumbs = new ArrayList<>();
        List<CursorFile> documentNote = new ArrayList<>();
        List<GalleryFile> galleryFiles = new ArrayList<>();
        
        for (CursorFile file : files) {
            String name = file.getName();
            
            // Skip directories - they'll be handled separately
            if (file.isDirectory()) {
                documentFiles.add(file);
                continue;
            }
            
            // V5 only: files have no extension - just 32-char alphanumeric random name
            // Files starting with "." (like index files) are automatically excluded
            boolean isV5File = !name.contains(".") && name.matches("[a-zA-Z0-9]{32}");

            if (isV5File) {
                // V5 file (32-char alphanumeric, no extension) - composite file with embedded thumbnail/note
                documentFiles.add(file);
            }
            // Else: unknown file type, skip it
        }

        // Process files and find their thumbnails/notes
        for (CursorFile file : documentFiles) {
            if (file.isDirectory()) {
                GalleryFile dir = GalleryFile.asDirectory(file);
                // Try to decrypt folder name if it looks like an encrypted folder
                tryDecryptFolderNameForGalleryFile(dir);
                galleryFiles.add(dir);
                continue;
            }
            
            file.setNameWithoutPrefix(FileStuff.getNameWithoutPrefix(file.getName()));
            
            // Try finding by legacy _1/_2 pattern first (V3 Fase 1)
            CursorFile foundThumb = findCursorFile(documentThumbs, file.getNameWithoutPrefix());
            CursorFile foundNote = findCursorFile(documentNote, file.getNameWithoutPrefix());
            
            // For V4 files, thumbnails/notes use .t.valv/.n.valv pattern (random names)
            // and cannot be correlated by base name. They will be found by:
            // - Decryption time: reading JSON_THUMB_NAME from main file metadata
            // - Or user accessing them individually
            
            galleryFiles.add(GalleryFile.asFile(file, foundThumb, foundNote));
        }
        return galleryFiles;
    }

    @Nullable
    private static CursorFile findCursorFile(@NonNull List<CursorFile> list, String nameWithoutPrefix) {
        for (CursorFile cf : list) {
            cf.setNameWithoutPrefix(FileStuff.getNameWithoutPrefix(cf.getName()));
            if (cf.getNameWithoutPrefix().startsWith(nameWithoutPrefix)) {
                return cf;
            }
        }
        return null;
    }

    public static Intent getPickFilesIntent(String mimeType) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    @NonNull
    public static List<Uri> uriListFromClipData(@Nullable ClipData clipData) {
        List<Uri> uris = new ArrayList<>();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                uris.add(clipData.getItemAt(i).getUri());
            }
        }
        return uris;
    }

    public static String getFilenameWithPathFromUri(@NonNull Uri uri) {
        String[] split = uri.getLastPathSegment().split(":", 2);
        return split[split.length - 1];
    }

    /**
     * Get the parent folder URI from a file URI.
     * For example, if the file URI is content://com.android.externalstorage.documents/tree/primary%3AValv/document/primary%3AValv%2Ffolder%2Ffile.enc
     * this returns the parent folder URI: content://com.android.externalstorage.documents/tree/primary%3AValv/document/primary%3AValv%2Ffolder
     * 
     * @param fileUri The file URI
     * @return The parent folder URI, or null if it cannot be determined
     */
    @Nullable
    public static Uri getParentFolderUri(@NonNull Uri fileUri) {
        try {
            String documentId = DocumentsContract.getDocumentId(fileUri);
            if (documentId == null) {
                return null;
            }
            
            // Document ID format: "primary:path/to/folder/file"
            // We need to get "primary:path/to/folder"
            int lastSlash = documentId.lastIndexOf('/');
            if (lastSlash < 0) {
                // File is at root level, return the tree URI
                String treeId = DocumentsContract.getTreeDocumentId(fileUri);
                if (treeId != null) {
                    return DocumentsContract.buildDocumentUriUsingTree(fileUri, treeId);
                }
                return null;
            }
            
            String parentDocumentId = documentId.substring(0, lastSlash);
            return DocumentsContract.buildDocumentUriUsingTree(fileUri, parentDocumentId);
        } catch (Exception e) {
            SecureLog.e(TAG, "Error getting parent folder URI", e);
            return null;
        }
    }

    /**
     * Get the nested path from a file URI.
     * This extracts the path after the tree root.
     * 
     * @param fileUri The file URI
     * @return The nested path, or empty string if at root
     */
    @NonNull
    public static String getNestedPathFromUri(@NonNull Uri fileUri) {
        try {
            String documentId = DocumentsContract.getDocumentId(fileUri);
            String treeId = DocumentsContract.getTreeDocumentId(fileUri);
            
            if (documentId == null || treeId == null) {
                return "";
            }
            
            // Document ID format: "primary:path/to/folder/file"
            // Tree ID format: "primary:Valv"
            // We need the path between tree root and the parent folder
            
            // First, get the parent folder path
            int lastSlash = documentId.lastIndexOf('/');
            if (lastSlash < 0) {
                return "";
            }
            
            String parentPath = documentId.substring(0, lastSlash);
            
            // Find where the tree path ends
            // The tree ID contains the root, like "primary:Valv"
            // The document ID contains the full path, like "primary:Valv/subfolder/file"
            String[] treeParts = treeId.split(":", 2);
            String[] parentParts = parentPath.split(":", 2);
            
            if (treeParts.length < 2 || parentParts.length < 2) {
                return "";
            }
            
            String treePath = treeParts[1]; // e.g., "Valv"
            String fullParentPath = parentParts[1]; // e.g., "Valv/subfolder"
            
            if (fullParentPath.equals(treePath)) {
                return "";
            }
            
            if (fullParentPath.startsWith(treePath + "/")) {
                // Get the relative path after the tree root
                String relativePath = fullParentPath.substring(treePath.length());
                // relativePath now starts with /, like "/subfolder"
                return relativePath;
            }
            
            return "";
        } catch (Exception e) {
            SecureLog.e(TAG, "Error getting nested path from URI", e);
            return "";
        }
    }

    public static String getFilenameFromUri(@NonNull Uri uri, boolean withoutPrefix) {
        String[] split = uri.getLastPathSegment().split("/");
        String s = split[split.length - 1];
        if (withoutPrefix) {
            if (s.startsWith(Encryption.ENCRYPTED_PREFIX)) {
                return s.substring(s.indexOf("-") + 1);
            } else {
                return s.substring(0, s.lastIndexOf("-"));
            }
        }
        return s;
    }

    /**
     * Get the display name for a folder URI.
     * If it's an encrypted folder, returns the decrypted name.
     * Otherwise, returns the original folder name.
     * 
     * @param uri The folder URI
     * @return The display name (decrypted if encrypted folder, or original name)
     */
    @NonNull
    public static String getDisplayNameFromUri(@NonNull Uri uri) {
        String folderName = getFilenameFromUri(uri, false);
        return tryGetDecryptedFolderName(folderName);
    }

    /**
     * Get the display path for a folder URI.
     * Decrypts any encrypted folder names in the path.
     * 
     * @param uri The folder URI
     * @return The display path with decrypted folder names
     */
    @NonNull
    public static String getDisplayPathFromUri(@NonNull Uri uri) {
        String path = getFilenameWithPathFromUri(uri);
        return decryptPathFolderNames(path);
    }

    /**
     * Try to decrypt a folder name if it looks like an encrypted folder.
     * Returns the original name if decryption fails or it's not encrypted.
     * 
     * @param folderName The folder name to try to decrypt
     * @return The decrypted name or the original name
     */
    @NonNull
    public static String tryGetDecryptedFolderName(@NonNull String folderName) {
        // Quick check - is it even a candidate for encrypted folder?
        if (!Encryption.looksLikeEncryptedFolder(folderName)) {
            return folderName;
        }
        
        // Check cache first
        FolderNameCache cache = FolderNameCache.getInstance();
        String cachedName = cache.get(folderName);
        if (cachedName != null) {
            return cachedName;
        }
        
        // Get password from current session
        char[] password = Password.getInstance().getPassword();
        if (password == null) {
            return folderName;
        }
        
        // Try to decrypt
        String decryptedName = Encryption.decryptFolderName(folderName, password);
        if (decryptedName != null) {
            cache.put(folderName, decryptedName);
            return decryptedName;
        }
        
        // Decryption failed - return original name
        return folderName;
    }

    /**
     * Decrypt encrypted folder names within a path.
     * 
     * @param path The path potentially containing encrypted folder names
     * @return The path with decrypted folder names
     */
    @NonNull
    private static String decryptPathFolderNames(@NonNull String path) {
        StringBuilder result = new StringBuilder();
        int len = path.length();
        int start = 0;
        boolean first = true;
        for (int i = 0; i <= len; i++) {
            if (i == len || path.charAt(i) == '/') {
                String part = path.substring(start, i);
                if (!first) {
                    result.append("/");
                } else {
                    first = false;
                }
                if (Encryption.looksLikeEncryptedFolder(part)) {
                    result.append(tryGetDecryptedFolderName(part));
                } else {
                    result.append(part);
                }
                start = i + 1;
            }
        }
        return result.toString();
    }

    public static String getNameWithoutPrefix(@NonNull String encryptedName) {
        // V5 pattern: 32-char alphanumeric with no extension - return as-is
        if (!encryptedName.contains(".") && encryptedName.matches("[a-zA-Z0-9]{32}")) {
            return encryptedName;
        }
        
        // Unknown pattern - return as-is
        return encryptedName;
    }

    @NonNull
    public static List<DocumentFile> getDocumentsFromDirectoryResult(Context context, List<Uri> uris) {
        List<DocumentFile> documentFiles = new ArrayList<>();
        if (context == null) {
            return documentFiles;
        }
        for (Uri uri : uris) {
            DocumentFile pickedFile = DocumentFile.fromSingleUri(context, uri);
            if (pickedFile != null && pickedFile.getType() != null && (pickedFile.getType().startsWith("image/") || pickedFile.getType().startsWith("video/")) &&
                    (!pickedFile.getName().endsWith(Encryption.ENCRYPTED_SUFFIX) || !pickedFile.getName().startsWith(Encryption.ENCRYPTED_PREFIX))) {
                documentFiles.add(pickedFile);
            }
        }
        return documentFiles;
    }

    @NonNull
    public static List<DocumentFile> getDocumentsFromShareIntent(Context context, @NonNull List<Uri> uris) {
        List<DocumentFile> documentFiles = new ArrayList<>();
        if (context == null) {
            return documentFiles;
        }
        for (Uri uri : uris) {
            DocumentFile pickedFile = DocumentFile.fromSingleUri(context, uri);
            if (pickedFile != null && pickedFile.getType() != null && (pickedFile.getType().startsWith("image/") || pickedFile.getType().startsWith("video/")) &&
                    (!pickedFile.getName().endsWith(Encryption.ENCRYPTED_SUFFIX) || !pickedFile.getName().startsWith(Encryption.ENCRYPTED_PREFIX))) {
                documentFiles.add(pickedFile);
            }
        }
        return documentFiles;
    }

    public static boolean deleteFile(Context context, @Nullable Uri uri) {
        if (uri == null) {
            return true;
        }
        DocumentFile documentFile = DocumentFile.fromSingleUri(context, uri);
        if (documentFile == null || !documentFile.exists()) {
            return true;
        }
        return documentFile.delete();
    }

    public static void deleteCache(Context context) {
        deleteDir(context.getCacheDir());
    }

    private static boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
            return dir.delete();
        } else if (dir != null && dir.isFile()) {
            return dir.delete();
        } else {
            return false;
        }
    }

    @Nullable
    public static String getExtension(String name) {
        try {
            return name.substring(name.lastIndexOf("."));
        } catch (Exception ignored) {
        }
        return null;
    }

    @Nullable
    public static String getExtensionOrDefault(GalleryFile file) {
        String extension = getExtension(file.getName());
        if (extension != null) {
            return extension;
        }
        return file.getFileType().extension;
    }

        public static boolean copyTo(Context context, GalleryFile sourceFile, DocumentFile directory) {
        if (sourceFile.getUri().getLastPathSegment().equals(directory.getUri().getLastPathSegment() + "/" + sourceFile.getEncryptedName())) {
            SecureLog.e(TAG, "moveTo: can't copy " + sourceFile.getUri().getLastPathSegment() + " to the same folder");
            return false;
        }
        String generatedName = StringStuff.getRandomFileName();
        int version = sourceFile.getVersion();
        
        String fileSuffix = Encryption.SUFFIX_V5;
        
        String fileName = generatedName + fileSuffix;
        DocumentFile file = directory.createFile("", fileName);
        
        if (file == null) {
            SecureLog.e(TAG, "copyTo: could not create file from " + sourceFile.getUri());
            return false;
        }
        return writeTo(context, sourceFile.getUri(), file.getUri());
    }

    public static boolean moveTo(Context context, GalleryFile sourceFile, DocumentFile directory) {
        if (sourceFile.getUri().getLastPathSegment().equals(directory.getUri().getLastPathSegment() + "/" + sourceFile.getEncryptedName())) {
            SecureLog.e(TAG, "moveTo: can't move " + sourceFile.getUri().getLastPathSegment() + " to the same folder");
            return false;
        }
        String nameWithoutPrefix = getNameWithoutPrefix(sourceFile.getEncryptedName());
        int version = sourceFile.getVersion();
        
        String fileSuffix = Encryption.SUFFIX_V5;
        
        String fileName = nameWithoutPrefix + fileSuffix;
        DocumentFile file = directory.createFile("", fileName);

        if (file == null) {
            SecureLog.e(TAG, "moveTo: could not create file from " + sourceFile.getUri());
            return false;
        }
        return writeTo(context, sourceFile.getUri(), file.getUri());
    }

    public static boolean writeTo(Context context, Uri src, Uri dest) {
        try {
            InputStream inputStream = new BufferedInputStream(context.getContentResolver().openInputStream(src), 1024 * 32);
            OutputStream outputStream = new BufferedOutputStream(context.getContentResolver().openOutputStream(dest));
            int read;
            byte[] buffer = new byte[2048];
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            try {
                outputStream.close();
                inputStream.close();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            SecureLog.e(TAG, "writeTo: failed to write: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Try to decrypt an encrypted folder name.
     * If decryption succeeds, sets isEncryptedFolder=true and decryptedFolderName on the GalleryFile.
     * If decryption fails (wrong password or not an encrypted folder), leaves the GalleryFile unchanged.
     * 
     * Uses in-memory cache to avoid expensive Argon2 key derivation for repeated folder names.
     * Cache is cleared when password changes or app is closed.
     * 
     * @param folder The GalleryFile representing the folder
     */
    private static void tryDecryptFolderNameForGalleryFile(@NonNull GalleryFile folder) {
        if (!folder.isDirectory()) {
            return;
        }
        
        String folderName = folder.getEncryptedName();
        if (folderName == null) {
            return;
        }
        
        // Quick heuristic check - encrypted folder names are long base64url strings
        if (!Encryption.looksLikeEncryptedFolder(folderName)) {
            return;
        }
        
        // Check cache first using the singleton FolderNameCache
        FolderNameCache cache = FolderNameCache.getInstance();
        String cachedResult = cache.get(folderName);
        if (cachedResult != null) {
            folder.setEncryptedFolder(true);
            folder.setDecryptedFolderName(cachedResult);
            return;
        }
        
        // Get password from current session
        char[] password = Password.getInstance().getPassword();
        if (password == null) {
            return;
        }
        
        // Try to decrypt
        String decryptedName = Encryption.decryptFolderName(folderName, password);
        
        // Cache the result if decryption succeeded
        if (decryptedName != null) {
            cache.put(folderName, decryptedName);
            folder.setEncryptedFolder(true);
            folder.setDecryptedFolderName(decryptedName);
        }
        // If decryption fails, folder remains as a regular folder with original name
    }
    
    /**
     * Clear the decrypted folder name cache.
     * Call this when password changes or app goes to background.
     */
    public static void clearDecryptedFolderCache() {
        FolderNameCache.getInstance().clear();
    }

    /**
     * Create an encrypted folder in the specified parent directory.
     * 
     * @param context The Android context
     * @param parentDirectory The parent directory to create the folder in
     * @param originalName The original folder name (max 30 characters)
     * @param password The user's password
     * @return The URI of the created folder, or null on error
     * @throws IllegalArgumentException if originalName exceeds 30 characters
     */
    @Nullable
    public static Uri createEncryptedFolder(Context context, @NonNull DocumentFile parentDirectory, 
            @NonNull String originalName, @NonNull char[] password) {
        if (originalName.length() > Encryption.MAX_FOLDER_NAME_LENGTH) {
            throw new IllegalArgumentException("Folder name exceeds maximum length of " + Encryption.MAX_FOLDER_NAME_LENGTH);
        }
        
        // Create encrypted folder name
        String encryptedName = Encryption.createEncryptedFolderName(originalName, password);
        if (encryptedName == null) {
            SecureLog.e(TAG, "createEncryptedFolder: Failed to encrypt folder name");
            return null;
        }
        
        // Create the directory using SAF
        DocumentFile newFolder = parentDirectory.createDirectory(encryptedName);
        if (newFolder == null) {
            SecureLog.e(TAG, "createEncryptedFolder: Failed to create directory");
            return null;
        }
        
        return newFolder.getUri();
    }

    /**
     * Recursively deletes a DocumentFile and all its contents.
     * @param file The DocumentFile to delete
     * @return true if all deletions succeeded
     */
    public static boolean deleteDocumentFileRecursive(@NonNull DocumentFile file) {
        boolean success = true;
        if (file.isDirectory()) {
            for (DocumentFile child : file.listFiles()) {
                success &= deleteDocumentFileRecursive(child);
            }
        }
        success &= file.delete();
        return success;
    }
}
