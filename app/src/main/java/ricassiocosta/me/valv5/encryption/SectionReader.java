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

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import ricassiocosta.me.valv5.security.SecureLog;

/**
 * V5 Section Reader for parsing encrypted files with multiple internal sections.
 *
 * Reads sections written by SectionWriter:
 * - FILE SECTION: [0x00][size_4bytes][content...]
 * - THUMBNAIL SECTION: [0x01][size_4bytes][content...]
 * - NOTE SECTION: [0x02][size_4bytes][content...]
 * - END MARKER: [0xFF]
 *
 * This class provides low-level I/O for reading and parsing section headers.
 */
public class SectionReader {
    private static final String TAG = "SectionReader";

    // Section type markers - must match SectionWriter
    public static final byte SECTION_TYPE_FILE = 0x00;
    public static final byte SECTION_TYPE_THUMBNAIL = 0x01;
    public static final byte SECTION_TYPE_NOTE = 0x02;
    public static final byte SECTION_TYPE_END = (byte) 0xFF;

    private final InputStream encryptedIn;
    private boolean endMarkerFound = false;

    /**
     * Create a new SectionReader that reads from an encrypted input stream.
     *
     * @param encryptedIn The CipherInputStream to read encrypted data from.
     *                    IMPORTANT: Do not wrap with BufferedInputStream as this can cause
     *                    bytes to be consumed ahead of what's been read, breaking section parsing.
     */
    public SectionReader(@NonNull InputStream encryptedIn) {
        // Don't wrap - the CipherInputStream should be used directly to avoid buffering issues
        this.encryptedIn = encryptedIn;
    }

    /**
     * Read the next section header and return section information.
     * Returns null if END marker is reached.
     *
     * @return SectionInfo with type and size, or null if no more sections
     * @throws IOException If reading fails or stream ends unexpectedly
     */
    @Nullable
    public SectionInfo readNextSection() throws IOException {
        if (endMarkerFound) {
            return null;
        }

        int typeByte = encryptedIn.read();
        if (typeByte == -1) {
            throw new EOFException("Unexpected end of stream while reading section type");
        }

        byte sectionType = (byte) typeByte;

        // Check for END marker
        if (sectionType == SECTION_TYPE_END) {
            endMarkerFound = true;
            return null;
        }

        // Validate section type
        if (!isValidSectionType(sectionType)) {
            SecureLog.e(TAG, "readNextSection: Invalid section type: 0x" + String.format("%02X", sectionType));
            throw new IOException("Invalid section type: 0x" + String.format("%02X", sectionType));
        }

        // Read size (4-byte big-endian)
        int size = readSize();
        if (size < 0) {
            throw new IOException("Invalid section size: " + size);
        }

        return new SectionInfo(sectionType, size);
    }

    /**
     * Check if the section type is valid.
     *
     * @param sectionType The section type byte
     * @return true if valid, false otherwise
     */
    private boolean isValidSectionType(byte sectionType) {
        return sectionType == SECTION_TYPE_FILE ||
                sectionType == SECTION_TYPE_THUMBNAIL ||
                sectionType == SECTION_TYPE_NOTE;
    }

    /**
     * Read exactly the specified number of bytes from the input stream.
     * Returns null if END marker is encountered before reading all bytes.
     *
     * @param size The number of bytes to read
     * @return The bytes read, or null if stream ended prematurely
     * @throws IOException If reading fails
     */
    @Nullable
    public byte[] readSectionContent(int size) throws IOException {
        byte[] buffer = new byte[size];
        int totalRead = 0;

        while (totalRead < size) {
            int bytesRead = encryptedIn.read(buffer, totalRead, size - totalRead);
            if (bytesRead == -1) {
                throw new EOFException("Unexpected end of stream while reading section content " +
                        "(expected " + size + " bytes, got " + totalRead + ")");
            }
            totalRead += bytesRead;
        }

        return buffer;
    }

    /**
     * Skip exactly the specified number of bytes in the input stream.
     * Note: We read and discard instead of using skip() because CipherInputStream.skip()
     * may not work correctly - it needs to decrypt bytes to advance.
     *
     * @param size The number of bytes to skip
     * @throws IOException If skipping fails
     */
    public void skipSectionContent(int size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int bytesRead = encryptedIn.read(buffer, 0, toRead);
            if (bytesRead <= 0) {
                throw new EOFException("Could not skip " + size + " bytes, only skipped " + (size - remaining));
            }
            remaining -= bytesRead;
        }
    }

    /**
     * Check if we've reached the END marker.
     *
     * @return true if END marker was found, false otherwise
     */
    public boolean hasEndMarker() {
        return endMarkerFound;
    }

    /**
     * Reset the end marker flag (for future section reading attempts).
     */
    public void resetEndMarker() {
        endMarkerFound = false;
    }

    /**
     * Read a 4-byte big-endian integer (size header).
     *
     * @return The integer value
     * @throws IOException If reading fails or stream ends
     */
    private int readSize() throws IOException {
        int byte1 = encryptedIn.read();
        int byte2 = encryptedIn.read();
        int byte3 = encryptedIn.read();
        int byte4 = encryptedIn.read();

        if (byte1 == -1 || byte2 == -1 || byte3 == -1 || byte4 == -1) {
            throw new EOFException("Unexpected end of stream while reading size header");
        }

        return ((byte1 & 0xFF) << 24) |
                ((byte2 & 0xFF) << 16) |
                ((byte3 & 0xFF) << 8) |
                (byte4 & 0xFF);
    }

    /**
     * Container for section information returned by readNextSection().
     */
    public static class SectionInfo {
        public final byte type;
        public final int size;

        public SectionInfo(byte type, int size) {
            this.type = type;
            this.size = size;
        }

        public boolean isFileSection() {
            return type == SECTION_TYPE_FILE;
        }

        public boolean isThumbnailSection() {
            return type == SECTION_TYPE_THUMBNAIL;
        }

        public boolean isNoteSection() {
            return type == SECTION_TYPE_NOTE;
        }

        @Override
        public String toString() {
            return "SectionInfo{" +
                    "type=0x" + String.format("%02X", type) +
                    ", size=" + size +
                    '}';
        }
    }
}
