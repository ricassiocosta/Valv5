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

package ricassiocosta.me.valv5.data;

import android.content.Context;
import android.icu.text.SimpleDateFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.interfaces.IOnDone;
import ricassiocosta.me.valv5.utils.FileStuff;
import ricassiocosta.me.valv5.security.SecureLog;

public class GalleryFile implements Comparable<GalleryFile> {
    private static final String TAG = "GalleryFile";
    private static final int FIND_FILES_NOT_STARTED = 0;
    private static final int FIND_FILES_RUNNING = 1;
    private static final int FIND_FILES_DONE = 2;

    private final AtomicInteger findFilesInDirectoryStatus = new AtomicInteger(FIND_FILES_NOT_STARTED);
    private GalleryFile firstFileInDirectoryWithThumb;

    private FileType fileType;
    private FileType overriddenFileType;
    private final String encryptedName, name;
    private final boolean isDirectory, isAllFolder;
    private final long lastModified, size;
    private final int version;
    private Encryption.ContentType contentType;
    private Uri fileUri;
    private Uri thumbUri, noteUri, decryptedCacheUri;
    private String originalName, nameWithPath, note, text;
    private int fileCount, orientation;
    private ricassiocosta.me.valv5.encryption.CompositeStreams compositeStreams;  // V5: Lazy-loaded composite streams
    
    // Encrypted folder support
    private boolean isEncryptedFolder;
    private String decryptedFolderName;

    private GalleryFile(String name) {
        this.fileUri = null;
        this.encryptedName = name;
        this.name = name;
        this.thumbUri = null;
        this.noteUri = null;
        this.decryptedCacheUri = null;
        this.lastModified = Long.MAX_VALUE;
        this.isDirectory = true;
        this.fileType = FileType.DIRECTORY;
        this.version = fileType.version;
        this.size = -1;
        this.isAllFolder = true;
        this.contentType = Encryption.ContentType.FILE;
        this.orientation = -1;
    }

    private GalleryFile(String name, String text) {
        this.fileUri = null;
        this.encryptedName = name;
        this.name = name;
        this.thumbUri = null;
        this.noteUri = null;
        this.decryptedCacheUri = null;
        this.lastModified = Long.MAX_VALUE;
        this.isDirectory = false;
          this.fileType = FileType.TEXT_V5;
        this.version = fileType.version;
        this.size = text.getBytes(StandardCharsets.UTF_8).length;
        this.isAllFolder = false;
        this.contentType = Encryption.ContentType.FILE;
        this.text = text;
        this.orientation = -1;
    }

    private GalleryFile(@NonNull CursorFile file, @Nullable CursorFile thumb, @Nullable CursorFile note) {
        this.fileUri = file.getUri();
        this.encryptedName = file.getName();
        this.thumbUri = thumb == null ? null : thumb.getUri();
        this.noteUri = note == null ? null : note.getUri();
        this.decryptedCacheUri = null;
        this.lastModified = file.getLastModified();
        this.isDirectory = false;
        // V5 only: type is stored in encrypted metadata, not in filename
        this.fileType = FileType.DIRECTORY;
        this.version = Encryption.ENCRYPTION_VERSION_5;
        this.size = file.getSize();
        this.isAllFolder = false;
        this.contentType = Encryption.ContentType.FILE;
        this.name = FileStuff.getNameWithoutPrefix(encryptedName);
        this.orientation = -1;
    }

    private GalleryFile(@NonNull Uri fileUri) {
        this.fileUri = fileUri;
        this.encryptedName = FileStuff.getFilenameFromUri(fileUri, false);
        this.name = encryptedName;
        this.thumbUri = null;
        this.noteUri = null;
        this.decryptedCacheUri = null;
        this.lastModified = System.currentTimeMillis();
        this.isDirectory = true;
        // V5 only: directories have no encrypted name
        this.fileType = FileType.DIRECTORY;
        this.version = Encryption.ENCRYPTION_VERSION_5;
        this.size = 0;
        this.isAllFolder = false;
        this.contentType = Encryption.ContentType.FILE;
        this.orientation = -1;
    }

    private GalleryFile(@NonNull CursorFile file) {
        this.fileUri = file.getUri();
        this.encryptedName = file.getName();
        this.name = encryptedName;
        this.thumbUri = null;
        this.noteUri = null;
        this.decryptedCacheUri = null;
        this.lastModified = file.getLastModified();
        this.isDirectory = true;
        this.fileType = FileType.DIRECTORY;
        this.version = fileType.version;
        this.size = 0;
        this.isAllFolder = false;
        this.contentType = Encryption.ContentType.FILE;
        this.orientation = -1;
    }

    public static GalleryFile asDirectory(Uri directoryUri) {
        return new GalleryFile(directoryUri);
    }

    public static GalleryFile asDirectory(CursorFile cursorFile) {
        return new GalleryFile(cursorFile);
    }

    public static GalleryFile asFile(CursorFile cursorFile, @Nullable CursorFile thumbUri, @Nullable CursorFile noteUri) {
        return new GalleryFile(cursorFile, thumbUri, noteUri);
    }

    public static GalleryFile asTempText(String text) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return new GalleryFile(simpleDateFormat.format(new Date()), text);
    }

    public static GalleryFile asAllFolder(String name) {
        return new GalleryFile(name);
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public void setFileTypeFromContent(boolean isAnimated) {
        if (originalName != null && originalName.toLowerCase().endsWith(".webp")) {
            if (isAnimated) {
                this.overriddenFileType = FileType.GIF_V5;
            } else {
                this.overriddenFileType = FileType.IMAGE_V5;
            }
        }
    }

    /**
     * Override the file type. Used when the actual type is determined from decrypted metadata.
     * @param fileType The FileType to set as override
     */
    public void setOverriddenFileType(FileType fileType) {
        this.overriddenFileType = fileType;
    }

    @Nullable
    public String getOriginalName() {
        return originalName;
    }

    public void setDecryptedCacheUri(Uri decryptedCacheUri) {
        this.decryptedCacheUri = decryptedCacheUri;
    }

    public int getVersion() {
        return version;
    }

    public Encryption.ContentType getContentType() {
        return contentType;
    }

    public void setContentType(Encryption.ContentType contentType) {
        this.contentType = contentType;
    }

    public void setOrientation(int orientation) {
        this.orientation = orientation;
    }

    public int getOrientation() {
        return orientation;
    }

    public AtomicInteger getFindFilesInDirectoryStatus() {
        return findFilesInDirectoryStatus;
    }

    public boolean isVideo() {
        return getFileType().type == FileType.TYPE_VIDEO;
    }

    public boolean isGif() {
        return getFileType().type == FileType.TYPE_GIF;
    }

    public boolean isImage() {
        return getFileType().type == FileType.TYPE_IMAGE;
    }

    public boolean isText() {
        return getFileType().type == FileType.TYPE_TEXT;
    }

    public long getSize() {
        return size;
    }

    @Nullable
    public Uri getDecryptedCacheUri() {
        return decryptedCacheUri;
    }


    public String getNameWithPath() {
        if (isAllFolder) {
            return name;
        }
        // For encrypted folders, return the decrypted name
        if (isEncryptedFolder && decryptedFolderName != null) {
            return decryptedFolderName;
        }
        if (nameWithPath == null) {
            nameWithPath = FileStuff.getDisplayPathFromUri(fileUri);
        }
        return nameWithPath;
    }

    public String getName() {
        return (originalName != null && !originalName.isEmpty()) ? originalName : name;
    }

    public String getEncryptedName() {
        return encryptedName;
    }

    public Uri getUri() {
        return fileUri;
    }

    @Nullable
    public Uri getThumbUri() {
        // For V5 files with composite thumbnail, load from cache
        if (version >= Encryption.ENCRYPTION_VERSION_5 && thumbUri == null && hasCompositeThumb()) {
            // This would need context, so we return null here
            // The adapter should handle V5 thumbnail loading separately
            return null;
        }
        return thumbUri;
    }

    public void setThumbUri(Uri thumbUri) {
        this.thumbUri = thumbUri;
    }

    @Nullable
    public Uri getNoteUri() {
        return noteUri;
    }

    public void setNoteUri(Uri noteUri) {
        this.noteUri = noteUri;
    }

    public void setFileUri(@NonNull Uri fileUri) {
        this.fileUri = fileUri;
    }

    @Nullable
    public String getNote() {
        return note;
    }

    public void setNote(@Nullable String note) {
        this.note = note;
    }

    @Nullable
    public String getText() {
        return text;
    }

    public void setText(@Nullable String text) {
        this.text = text;
    }

    public boolean hasThumb() {
        // Check traditional thumb file (V1-V4)
        if (thumbUri != null) {
            return true;
        }
        // Check if this might be a V5 composite file with embedded thumbnail
        return mayBeV5CompositeFile();
    }

    public boolean hasNote() {
        // Check traditional note file (V1-V4)
        if (noteUri != null || note != null) {
            return true;
        }
        // Check V5 composite note section
        return hasCompositeNote();
    }

    /**
     * V5: Set CompositeStreams for lazy loading of sections.
     * Used for V5 composite files where thumbnail/note are stored within the main file.
     */
    public void setCompositeStreams(@Nullable ricassiocosta.me.valv5.encryption.CompositeStreams compositeStreams) {
        this.compositeStreams = compositeStreams;
    }

    /**
     * V5: Get CompositeStreams if available (for reading sections from composite file).
     */
    @Nullable
    public ricassiocosta.me.valv5.encryption.CompositeStreams getCompositeStreams() {
        return compositeStreams;
    }

    /**
     * V5: Check if this is a V5 composite file with a thumbnail section.
     * Returns true if compositeStreams is available and has thumbnail.
     */
    public boolean hasCompositeThumb() {
        if (compositeStreams == null) {
            return false;
        }
        try {
            return compositeStreams.hasThumbnailSection();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Check if this file might be a V5 composite file (thumbnail embedded).
     * Returns true if:
     * - The file uses .valv generic suffix (V3+) OR has no extension (V5)
     * - No separate thumbnail URI was found
     * - This is not a text file
     * 
     * This is used to try loading composite thumbnails for files where
     * we cannot determine the version from the filename alone.
     */
    public boolean mayBeV5CompositeFile() {
        if (encryptedName == null || isDirectory || isText() || thumbUri != null) {
            return false;
        }
        
        // V3+ style .valv files (not thumbnails or notes)
          boolean isValvFile = !encryptedName.contains(".") && encryptedName.matches("[a-zA-Z0-9]{32}");        // V5 files have no extension - just an alphanumeric random name (32 chars)
        boolean isV5NoExtension = !encryptedName.contains(".") 
                && encryptedName.matches("[a-zA-Z0-9]{32}");
        
        return isValvFile || isV5NoExtension;
    }

    /**
     * Check if this file is ready for viewing in the gallery viewer.
     * For V5 composite files, this means the thumbnail has been loaded (metadata extracted).
     * For directories, text files, and files with pre-existing thumbnails, always returns true.
     * 
     * This prevents opening files in the viewer before their metadata is fully loaded,
     * which could cause crashes or display issues.
     */
    public boolean isReadyForViewing() {
        // Directories and special folders are always ready
        if (isDirectory || isAllFolder) {
            return true;
        }
        
        // Text files don't need thumbnail loading
        if (isText()) {
            return true;
        }
        
        // If the file might be a V5 composite file, check if thumbnail was loaded
        if (mayBeV5CompositeFile()) {
            // For V5 files, consider ready if thumbUri is set,
            // or if the file is a text file or has a note section (which may not require a thumbnail)
            return thumbUri != null;
        }
        
        // For other files (V1-V4 with separate thumbnail files), always ready
        return true;
    }

    /**
     * V5: Check if this is a V5 composite file with a note section.
     */
    public boolean hasCompositeNote() {
        if (compositeStreams == null) {
            return false;
        }
        try {
            return compositeStreams.hasNoteSection();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public FileType getFileType() {
        if (overriddenFileType != null) {
            return overriddenFileType;
        }
        return fileType;
    }

    public boolean isAllFolder() {
        return isAllFolder;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    /**
     * Check if this is an encrypted folder (name encrypted with user password).
     */
    public boolean isEncryptedFolder() {
        return isEncryptedFolder;
    }

    /**
     * Set whether this folder is an encrypted folder.
     */
    public void setEncryptedFolder(boolean encryptedFolder) {
        this.isEncryptedFolder = encryptedFolder;
    }

    /**
     * Get the decrypted folder name (original name before encryption).
     * Returns null if this is not an encrypted folder or if decryption failed.
     */
    @Nullable
    public String getDecryptedFolderName() {
        return decryptedFolderName;
    }

    /**
     * Set the decrypted folder name.
     */
    public void setDecryptedFolderName(@Nullable String decryptedFolderName) {
        this.decryptedFolderName = decryptedFolderName;
    }

    /**
     * Get the display name for this folder.
     * Returns the decrypted name if this is an encrypted folder and decryption succeeded,
     * otherwise returns the raw folder name.
     */
    @NonNull
    public String getDisplayName() {
        if (isEncryptedFolder && decryptedFolderName != null) {
            return decryptedFolderName;
        }
        return name != null ? name : (encryptedName != null ? encryptedName : "[Unknown]");
    }

    public long getLastModified() {
        return lastModified;
    }

    @Nullable
    public GalleryFile getFirstFile() {
        return firstFileInDirectoryWithThumb;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void findFilesInDirectory(Context context, IOnDone onDone) {
        if (!isDirectory || fileUri == null || !findFilesInDirectoryStatus.compareAndSet(FIND_FILES_NOT_STARTED, FIND_FILES_RUNNING)) {
            return;
        }
        new Thread(() -> {
            List<GalleryFile> galleryFiles = FileStuff.getFilesInFolder(context, fileUri, false);
            if (!galleryFiles.isEmpty()) {
                GalleryFile fileToCheck = null;
                for (GalleryFile f : galleryFiles) {
                    if (!f.isDirectory()) {
                        fileToCheck = f;
                        break;
                    }
                }

                if (fileToCheck != null) {
                    try {
                        char[] password = Password.getInstance().getPassword();
                        if (fileToCheck.getThumbUri() != null) {
                            Encryption.checkPassword(context, fileToCheck.getThumbUri(), password, fileToCheck.getVersion(), false);
                        } else {
                            Encryption.checkPassword(context, fileToCheck.getUri(), password, fileToCheck.getVersion(), false);
                        }
                    } catch (InvalidPasswordException e) {
                        this.fileCount = 0;
                        this.firstFileInDirectoryWithThumb = null;
                        findFilesInDirectoryStatus.set(FIND_FILES_DONE);
                        if (onDone != null) {
                            onDone.onDone();
                        }
                        return;
                    } catch (Exception e) {
                        SecureLog.e(TAG, "Error checking password for folder " + fileUri, e);
                        this.fileCount = 0;
                        this.firstFileInDirectoryWithThumb = null;
                        findFilesInDirectoryStatus.set(FIND_FILES_DONE);
                        if (onDone != null) {
                            onDone.onDone();
                        }
                        return;
                    }
                }
            }

            this.fileCount = 0;
            this.firstFileInDirectoryWithThumb = null;
            for (GalleryFile f : galleryFiles) {
                if (!f.isDirectory() && f.hasThumb()) {
                    this.firstFileInDirectoryWithThumb = f;
                    break;
                }
            }
            this.fileCount = galleryFiles.size();
            findFilesInDirectoryStatus.set(FIND_FILES_DONE);
            if (onDone != null) {
                onDone.onDone();
            }
        }).start();
    }

    public void resetFilesInDirectory() {
        findFilesInDirectoryStatus.set(FIND_FILES_NOT_STARTED);
    }

    @Override
    public int compareTo(GalleryFile o) {
        // Directories always come first
        if (this.isDirectory && !o.isDirectory) return -1;
        if (!this.isDirectory && o.isDirectory) return 1;
        // Then sort by last modified (newest first)
        int cmp = Long.compare(o.lastModified, this.lastModified);
        if (cmp != 0) return cmp;
        // If lastModified is equal, sort by name (encryptedName)
        if (this.encryptedName != null && o.encryptedName != null) {
            return this.encryptedName.compareToIgnoreCase(o.encryptedName);
        } else if (this.encryptedName != null) {
            return -1;
        } else if (o.encryptedName != null) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GalleryFile that = (GalleryFile) o;
        return size == that.size && getFileType() == that.getFileType() && Objects.equals(encryptedName, that.encryptedName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(size, getFileType(), encryptedName);
    }
}
