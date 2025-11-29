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

package se.arctosoft.vault.utils;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

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

import se.arctosoft.vault.data.CursorFile;
import se.arctosoft.vault.data.GalleryFile;
import se.arctosoft.vault.data.Password;
import se.arctosoft.vault.encryption.Encryption;
import se.arctosoft.vault.exception.InvalidPasswordException;

public class FileStuff {
    private static final String TAG = "FileStuff";

    @NonNull
    public static List<GalleryFile> getFilesInFolder(Context context, Uri pickedDir, boolean checkDecryptable) {
        //Log.e(TAG, "getFilesInFolder: " + pickedDir);
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
            if (!name.startsWith(Encryption.ENCRYPTED_PREFIX) && !name.endsWith(Encryption.ENCRYPTED_SUFFIX) && !file.isDirectory()) {
                continue;
            }

            // Check V1/V2 suffixes FIRST before checking V3/V4 patterns
            if (name.endsWith(Encryption.SUFFIX_THUMB) || name.startsWith(Encryption.PREFIX_THUMB)) {
                // V1/V2 thumbnails
                documentThumbs.add(file);
            } else if (name.endsWith(Encryption.SUFFIX_NOTE_FILE) || name.startsWith(Encryption.PREFIX_NOTE_FILE)) {
                // V1/V2 notes
                documentNote.add(file);
            } else if (name.endsWith(Encryption.SUFFIX_GENERIC_FILE) && !name.startsWith(Encryption.ENCRYPTED_PREFIX)) {
                // V3/V4 file - check if it's a thumbnail or note based on pattern
                if (name.endsWith(".t" + Encryption.SUFFIX_GENERIC_FILE)) {
                    // V4: Thumbnail with .t.valv suffix
                    documentThumbs.add(file);
                } else if (name.endsWith(".n" + Encryption.SUFFIX_GENERIC_FILE)) {
                    // V4: Note with .n.valv suffix
                    documentNote.add(file);
                } else if (name.endsWith("_1" + Encryption.SUFFIX_GENERIC_FILE)) {
                    // V3 Fase 1: Thumbnail with _1.valv suffix
                    documentThumbs.add(file);
                } else if (name.endsWith("_2" + Encryption.SUFFIX_GENERIC_FILE)) {
                    // V3 Fase 1: Note with _2.valv suffix
                    documentNote.add(file);
                } else {
                    // Plain main file (V3/V4)
                    documentFiles.add(file);
                }
            } else {
                documentFiles.add(file);
            }
        }

        // Process files and find their thumbnails/notes
        for (CursorFile file : documentFiles) {
            if (file.isDirectory()) {
                galleryFiles.add(GalleryFile.asDirectory(file));
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
        String[] split = uri.getLastPathSegment().split(":");
        return split[split.length - 1];
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

    public static String getNameWithoutPrefix(@NonNull String encryptedName) {
        // V3 pattern: basename[_1|_2].valv where _1 is thumbnail, _2 is note
        if (encryptedName.endsWith(Encryption.SUFFIX_GENERIC_FILE)) {
            // V3 file
            String baseName = encryptedName.substring(0, encryptedName.lastIndexOf(Encryption.SUFFIX_GENERIC_FILE));
            // Remove _1 (thumbnail) or _2 (note) suffix if present
            if (baseName.endsWith("_1") || baseName.endsWith("_2")) {
                baseName = baseName.substring(0, baseName.length() - 2);
            }
            return baseName;
        } else if (encryptedName.startsWith(Encryption.ENCRYPTED_PREFIX)) {
            return encryptedName.substring(encryptedName.indexOf("-") + 1);
        } else {
            return encryptedName.substring(0, encryptedName.lastIndexOf("-"));
        }
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
            Log.e(TAG, "moveTo: can't copy " + sourceFile.getUri().getLastPathSegment() + " to the same folder");
            return false;
        }
        String generatedName = StringStuff.getRandomFileName();
        int version = sourceFile.getVersion();
        
        String fileSuffix = version >= 3 ? Encryption.SUFFIX_GENERIC_FILE : (version < 2 ? sourceFile.getFileType().suffixPrefix : sourceFile.getFileType().suffixPrefix);
        String thumbSuffix = version >= 3 ? Encryption.SUFFIX_GENERIC_THUMB : (version < 2 ? Encryption.PREFIX_THUMB : Encryption.SUFFIX_THUMB);
        String noteSuffix = version >= 3 ? Encryption.SUFFIX_GENERIC_NOTE : (version < 2 ? Encryption.PREFIX_NOTE_FILE : Encryption.SUFFIX_NOTE_FILE);
        
        String fileName = version >= 3 ? (generatedName + fileSuffix) : (version < 2 ? sourceFile.getFileType().suffixPrefix + generatedName : generatedName + sourceFile.getFileType().suffixPrefix);
        DocumentFile file = directory.createFile("", fileName);
        DocumentFile thumbFile = sourceFile.getThumbUri() == null ? null : directory.createFile("", version >= 3 ? (generatedName + "_1" + thumbSuffix) : (version < 2 ? thumbSuffix + generatedName : generatedName + thumbSuffix));
        DocumentFile noteFile = sourceFile.getNoteUri() == null ? null : directory.createFile("", version >= 3 ? (generatedName + "_2" + noteSuffix) : (version < 2 ? noteSuffix + generatedName : generatedName + noteSuffix));

        if (file == null) {
            Log.e(TAG, "copyTo: could not create file from " + sourceFile.getUri());
            return false;
        }
        if (thumbFile != null) {
            writeTo(context, sourceFile.getThumbUri(), thumbFile.getUri());
        }
        if (noteFile != null) {
            writeTo(context, sourceFile.getNoteUri(), noteFile.getUri());
        }
        return writeTo(context, sourceFile.getUri(), file.getUri());
    }

    public static boolean moveTo(Context context, GalleryFile sourceFile, DocumentFile directory) {
        if (sourceFile.getUri().getLastPathSegment().equals(directory.getUri().getLastPathSegment() + "/" + sourceFile.getEncryptedName())) {
            Log.e(TAG, "moveTo: can't move " + sourceFile.getUri().getLastPathSegment() + " to the same folder");
            return false;
        }
        String nameWithoutPrefix = getNameWithoutPrefix(sourceFile.getEncryptedName());
        int version = sourceFile.getVersion();
        
        String fileSuffix = version >= 3 ? Encryption.SUFFIX_GENERIC_FILE : (version < 2 ? sourceFile.getFileType().suffixPrefix : sourceFile.getFileType().suffixPrefix);
        String thumbSuffix = version >= 3 ? Encryption.SUFFIX_GENERIC_THUMB : (version < 2 ? Encryption.PREFIX_THUMB : Encryption.SUFFIX_THUMB);
        String noteSuffix = version >= 3 ? Encryption.SUFFIX_GENERIC_NOTE : (version < 2 ? Encryption.PREFIX_NOTE_FILE : Encryption.SUFFIX_NOTE_FILE);
        
        String fileName = version >= 3 ? (nameWithoutPrefix + fileSuffix) : (version < 2 ? sourceFile.getFileType().suffixPrefix + nameWithoutPrefix : nameWithoutPrefix + sourceFile.getFileType().suffixPrefix);
        DocumentFile file = directory.createFile("", fileName);
        DocumentFile thumbFile = sourceFile.getThumbUri() == null ? null : directory.createFile("", version >= 3 ? (nameWithoutPrefix + "_1" + thumbSuffix) : (version < 2 ? thumbSuffix + nameWithoutPrefix : nameWithoutPrefix + thumbSuffix));
        DocumentFile noteFile = sourceFile.getNoteUri() == null ? null : directory.createFile("", version >= 3 ? (nameWithoutPrefix + "_2" + noteSuffix) : (version < 2 ? noteSuffix + nameWithoutPrefix : nameWithoutPrefix + noteSuffix));

        if (file == null) {
            Log.e(TAG, "moveTo: could not create file from " + sourceFile.getUri());
            return false;
        }
        if (thumbFile != null) {
            writeTo(context, sourceFile.getThumbUri(), thumbFile.getUri());
        }
        if (noteFile != null) {
            writeTo(context, sourceFile.getNoteUri(), noteFile.getUri());
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
            Log.e(TAG, "writeTo: failed to write: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
