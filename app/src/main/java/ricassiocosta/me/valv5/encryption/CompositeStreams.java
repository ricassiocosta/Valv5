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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * V5 Composite Streams wrapper for reading sections from encrypted composite files.
 *
 * This class wraps a CipherInputStream and provides convenient methods to access
 * different sections (FILE, THUMBNAIL, NOTE) of a V5 composite encrypted file.
 *
 * It uses lazy loading: sections are only read and cached when explicitly requested.
 *
 * Usage:
 * 1. Create with CipherInputStream
 * 2. Call getFileSection() to get file content stream
 * 3. Call hasThumbnailSection() to check if thumbnail exists
 * 4. Call getThumbnailInputStream() to lazily load thumbnail
 * 5. Call getNoteSection() to get note text
 */
public class CompositeStreams {
    private static final String TAG = "CompositeStreams";

    private final InputStream encryptedIn;
    private final SectionReader sectionReader;

    // Cached sections (lazy loaded)
    private SectionReader.SectionInfo fileInfo = null;
    private SectionReader.SectionInfo thumbnailInfo = null;
    private SectionReader.SectionInfo noteInfo = null;

    // Track consumption state
    private boolean fileContentConsumed = false;
    private boolean thumbnailHeaderRead = false;
    private boolean thumbnailContentConsumed = false;
    private boolean noteHeaderRead = false;
    private boolean noteContentConsumed = false;

    private byte[] cachedFileContent = null;
    private byte[] cachedThumbnail = null;
    private byte[] cachedNote = null;

    /**
     * Create a new CompositeStreams wrapper.
     *
     * @param encryptedIn The CipherInputStream from which to read sections
     */
    public CompositeStreams(@NonNull InputStream encryptedIn) {
        this.encryptedIn = encryptedIn;
        this.sectionReader = new SectionReader(encryptedIn);
    }

    /**
     * Get a stream to read the FILE section (main content) with streaming (no full cache).
     * For large files like videos, this reads on-demand without caching the entire file.
     * 
     * @return InputStream for file content
     * @throws IOException If reading fails
     */
    @NonNull
    public InputStream getFileSectionStreaming() throws IOException {
        if (fileInfo == null) {
            // Read FILE section header
            SectionReader.SectionInfo info = sectionReader.readNextSection();
            if (info == null || !info.isFileSection()) {
                throw new IOException("File section not found in composite file, got: " + info);
            }
            fileInfo = info;
        }

        // Return a LimitedInputStream that reads from encryptedIn with size limit
        // This allows streaming without loading entire file into memory
        fileContentConsumed = true;
        return new LimitedInputStream(encryptedIn, fileInfo.size);
    }

    /**
     * Get a stream to read the FILE section (main content) with full caching.
     * This is typically the first section in a V5 file.
     * The content is cached on first read to allow multiple calls.
     * Use this for small files or when multiple reads are needed.
     *
     * @return InputStream for file content
     * @throws IOException If reading fails
     */
    @NonNull
    public InputStream getFileSection() throws IOException {
        // Return cached content if available
        if (cachedFileContent != null) {
            return new ByteArrayInputStream(cachedFileContent);
        }
        
        if (fileInfo == null) {
            // Read FILE section header
            SectionReader.SectionInfo info = sectionReader.readNextSection();
            if (info == null || !info.isFileSection()) {
                throw new IOException("File section not found in composite file, got: " + info);
            }
            fileInfo = info;
        }

        // Read and cache the entire file content
        cachedFileContent = new byte[(int) fileInfo.size];
        int totalRead = 0;
        while (totalRead < fileInfo.size) {
            int read = encryptedIn.read(cachedFileContent, totalRead, (int) (fileInfo.size - totalRead));
            if (read == -1) {
                throw new IOException("Unexpected end of stream while reading FILE section");
            }
            totalRead += read;
        }

        fileContentConsumed = true;
        return new ByteArrayInputStream(cachedFileContent);
    }

    /**
     * Check if a THUMBNAIL section exists in this composite file.
     *
     * @return true if thumbnail section is present
     * @throws IOException If reading fails
     */
    public boolean hasThumbnailSection() throws IOException {
        if (thumbnailInfo == null && !thumbnailHeaderRead) {
            // Read FILE header if not already done
            if (fileInfo == null) {
                SectionReader.SectionInfo info = sectionReader.readNextSection();
                if (info != null && info.isFileSection()) {
                    fileInfo = info;
                } else {
                    // No FILE section found, this shouldn't happen
                    android.util.Log.e(TAG, "hasThumbnailSection: No FILE section found!");
                    return false;
                }
            }

            // Skip FILE content if not yet consumed
            if (!fileContentConsumed && fileInfo != null) {
                sectionReader.skipSectionContent(fileInfo.size);
                fileContentConsumed = true;  // Mark as consumed after skip
            }

            // Read next header
            SectionReader.SectionInfo info = sectionReader.readNextSection();
            thumbnailHeaderRead = true;
            if (info != null && info.isThumbnailSection()) {
                thumbnailInfo = info;
            }
        }
        
        return thumbnailInfo != null;
    }

    /**
     * Get a stream to read the THUMBNAIL section.
     * Only valid if hasThumbnailSection() returns true.
     *
     * Lazy-loads the thumbnail into memory cache and returns a ByteArrayInputStream.
     *
     * @return InputStream for thumbnail content, or null if no thumbnail
     * @throws IOException If reading fails
     */
    @Nullable
    public InputStream getThumbnailInputStream() throws IOException {
        if (!hasThumbnailSection()) {
            return null;
        }

        if (cachedThumbnail == null) {
            // Read thumbnail section
            cachedThumbnail = sectionReader.readSectionContent(thumbnailInfo.size);
            thumbnailContentConsumed = true;
        }

        return new ByteArrayInputStream(cachedThumbnail);
    }

    /**
     * Get the thumbnail bytes directly (cached).
     *
     * @return Thumbnail byte array, or null if no thumbnail
     * @throws IOException If reading fails
     */
    @Nullable
    public byte[] getThumbnailBytes() throws IOException {
        getThumbnailInputStream(); // Ensure loaded
        return cachedThumbnail;
    }

    /**
     * Check if a NOTE section exists in this composite file.
     *
     * @return true if note section is present
     * @throws IOException If reading fails
     */
    public boolean hasNoteSection() throws IOException {
        if (noteInfo == null && !noteHeaderRead) {
            // Ensure FILE header is read
            if (fileInfo == null) {
                SectionReader.SectionInfo info = sectionReader.readNextSection();
                if (info != null && info.isFileSection()) {
                    fileInfo = info;
                } else {
                    return false;
                }
            }

            // Skip FILE content if not yet consumed
            if (!fileContentConsumed && fileInfo != null) {
                sectionReader.skipSectionContent(fileInfo.size);
            }

            // Ensure THUMBNAIL header is read
            if (thumbnailInfo == null && !thumbnailHeaderRead) {
                SectionReader.SectionInfo info = sectionReader.readNextSection();
                thumbnailHeaderRead = true;
                if (info != null && info.isThumbnailSection()) {
                    thumbnailInfo = info;
                }
            }

            // Skip THUMBNAIL content if not yet consumed
            if (!thumbnailContentConsumed && thumbnailInfo != null) {
                sectionReader.skipSectionContent(thumbnailInfo.size);
            }

            // Read NOTE header
            SectionReader.SectionInfo info = sectionReader.readNextSection();
            noteHeaderRead = true;
            if (info != null && info.isNoteSection()) {
                noteInfo = info;
            }
        }
        
        return noteInfo != null;
    }

    /**
     * Get the NOTE section as a string (UTF-8).
     * Only valid if hasNoteSection() returns true.
     *
     * Lazy-loads the note into memory cache.
     *
     * @return Note text as string, or null if no note
     * @throws IOException If reading fails
     */
    @Nullable
    public String getNoteSection() throws IOException {
        if (!hasNoteSection()) {
            return null;
        }

        if (cachedNote == null) {
            // Read note section
            cachedNote = sectionReader.readSectionContent(noteInfo.size);
            noteContentConsumed = true;
        }

        return new String(cachedNote, java.nio.charset.StandardCharsets.UTF_8);
    }


    /**
     * Helper stream that limits reading to a specific number of bytes.
     * Used to prevent reading past the FILE section boundary.
     */
    private static class LimitedInputStream extends InputStream {
        private final InputStream underlying;
        private final long limit;
        private long bytesRead = 0;
        private final Runnable onClose;

        LimitedInputStream(InputStream underlying, long limit) {
            this(underlying, limit, null);
        }

        LimitedInputStream(InputStream underlying, long limit, @Nullable Runnable onClose) {
            this.underlying = underlying;
            this.limit = limit;
            this.onClose = onClose;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= limit) {
                if (bytesRead == limit && onClose != null) {
                    onClose.run();
                }
                return -1;
            }
            int value = underlying.read();
            if (value != -1) {
                bytesRead++;
                if (bytesRead >= limit && onClose != null) {
                    onClose.run();
                }
            }
            return value;
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            if (bytesRead >= limit) {
                if (bytesRead == limit && onClose != null) {
                    onClose.run();
                }
                return -1;
            }
            long toRead = Math.min(len, limit - bytesRead);
            int bytesRead = underlying.read(b, off, (int) toRead);
            if (bytesRead > 0) {
                this.bytesRead += bytesRead;
                if (this.bytesRead >= limit && onClose != null) {
                    onClose.run();
                }
            }
            return bytesRead;
        }

        @Override
        public void close() throws IOException {
            // Don't close underlying stream - it's still needed for other sections
        }
    }
}
