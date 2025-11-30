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

package se.arctosoft.vault.encryption;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * V5 Section Writer for composing encrypted files with multiple internal sections.
 *
 * Format:
 * - FILE SECTION: [0x00][size_4bytes][content...]
 * - THUMBNAIL SECTION: [0x01][size_4bytes][content...]
 * - NOTE SECTION: [0x02][size_4bytes][content...]
 * - END MARKER: [0xFF]
 *
 * Each section is written to the encrypted output stream.
 * This class handles the low-level I/O for section markers and size headers.
 */
public class SectionWriter {
    private static final String TAG = "SectionWriter";

    // Section type markers
    public static final byte SECTION_TYPE_FILE = 0x00;
    public static final byte SECTION_TYPE_THUMBNAIL = 0x01;
    public static final byte SECTION_TYPE_NOTE = 0x02;
    public static final byte SECTION_TYPE_END = (byte) 0xFF;

    private final OutputStream encryptedOut;

    /**
     * Create a new SectionWriter that writes to an encrypted output stream.
     *
     * @param encryptedOut The CipherOutputStream to write encrypted data to
     */
    public SectionWriter(@NonNull OutputStream encryptedOut) {
        this.encryptedOut = encryptedOut;
    }

    /**
     * Write a FILE section containing the main encrypted file content.
     *
     * @param fileInputStream Input stream with file data
     * @param fileSize        Size of the file in bytes
     * @throws IOException If writing fails
     */
    public void writeFileSection(@NonNull InputStream fileInputStream, long fileSize)
            throws IOException {
        writeSection(SECTION_TYPE_FILE, fileInputStream, fileSize);
    }

    /**
     * Write a THUMBNAIL section containing the encrypted thumbnail.
     *
     * @param thumbInputStream Input stream with thumbnail data (JPEG/PNG)
     * @param thumbSize        Size of thumbnail in bytes
     * @throws IOException If writing fails
     */
    public void writeThumbnailSection(@NonNull InputStream thumbInputStream, long thumbSize)
            throws IOException {
        writeSection(SECTION_TYPE_THUMBNAIL, thumbInputStream, thumbSize);
    }

    /**
     * Write a NOTE section containing encrypted note text.
     *
     * @param noteInputStream Input stream with note data (UTF-8)
     * @param noteSize        Size of note in bytes
     * @throws IOException If writing fails
     */
    public void writeNoteSection(@NonNull InputStream noteInputStream, long noteSize)
            throws IOException {
        writeSection(SECTION_TYPE_NOTE, noteInputStream, noteSize);
    }

    /**
     * Write a NOTE section from a byte array.
     * Convenience method for writing text notes.
     *
     * @param noteBytes UTF-8 encoded note text
     * @throws IOException If writing fails
     */
    public void writeNoteSection(@NonNull byte[] noteBytes) throws IOException {
        encryptedOut.write(SECTION_TYPE_NOTE);
        writeSize(noteBytes.length);
        encryptedOut.write(noteBytes);
    }

    /**
     * Write the END marker to indicate no more sections.
     * Must be called after all sections are written.
     *
     * @throws IOException If writing fails
     */
    public void writeEndMarker() throws IOException {
        encryptedOut.write(SECTION_TYPE_END);
    }

    /**
     * Internal method to write a generic section.
     * Format: [type_1byte][size_4bytes][content]
     *
     * @param sectionType     Type of section (SECTION_TYPE_*)
     * @param contentInput    Input stream with section content
     * @param contentSize     Size in bytes
     * @throws IOException If writing fails or contentSize exceeds Integer.MAX_VALUE
     */
    private void writeSection(@NonNull byte sectionType, @NonNull InputStream contentInput,
                              long contentSize) throws IOException {
        if (contentSize > Integer.MAX_VALUE) {
            throw new IOException("Section size exceeds maximum allowed: " + contentSize);
        }

        // Write section type marker
        encryptedOut.write(sectionType);

        // Write size as 4-byte big-endian integer
        writeSize((int) contentSize);

        // Write content from input stream
        byte[] buffer = new byte[8192];
        long bytesWritten = 0;
        int bytesRead;

        while ((bytesRead = contentInput.read(buffer)) != -1) {
            encryptedOut.write(buffer, 0, bytesRead);
            bytesWritten += bytesRead;
        }

        if (bytesWritten != contentSize) {
            throw new IOException("Expected " + contentSize + " bytes but got " + bytesWritten);
        }
    }

    /**
     * Write a 4-byte big-endian integer (size header).
     *
     * @param value The value to write
     * @throws IOException If writing fails
     */
    private void writeSize(int value) throws IOException {
        encryptedOut.write((value >> 24) & 0xFF);
        encryptedOut.write((value >> 16) & 0xFF);
        encryptedOut.write((value >> 8) & 0xFF);
        encryptedOut.write(value & 0xFF);
    }
}
