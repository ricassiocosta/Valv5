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

package se.arctosoft.vault.data;

import androidx.annotation.NonNull;

import se.arctosoft.vault.encryption.Encryption;

public enum FileType {
    DIRECTORY(0, null, null, 1),
    IMAGE_V1(1, ".jpg", Encryption.PREFIX_IMAGE_FILE, 1),
    IMAGE_V2(1, ".jpg", Encryption.SUFFIX_IMAGE_FILE, 2),
    IMAGE_V3(1, ".jpg", Encryption.SUFFIX_GENERIC_FILE, 3),
    IMAGE_V4(1, ".jpg", Encryption.SUFFIX_GENERIC_FILE, 4),
    IMAGE_V5(1, ".jpg", Encryption.SUFFIX_V5, 5),  // V5: No extension
    GIF_V1(2, ".gif", Encryption.PREFIX_GIF_FILE, 1),
    GIF_V2(2, ".gif", Encryption.SUFFIX_GIF_FILE, 2),
    GIF_V3(2, ".gif", Encryption.SUFFIX_GENERIC_FILE, 3),
    GIF_V4(2, ".gif", Encryption.SUFFIX_GENERIC_FILE, 4),
    GIF_V5(2, ".gif", Encryption.SUFFIX_V5, 5),  // V5: No extension
    VIDEO_V1(3, ".mp4", Encryption.PREFIX_VIDEO_FILE, 1),
    VIDEO_V2(3, ".mp4", Encryption.SUFFIX_VIDEO_FILE, 2),
    VIDEO_V3(3, ".mp4", Encryption.SUFFIX_GENERIC_FILE, 3),
    VIDEO_V4(3, ".mp4", Encryption.SUFFIX_GENERIC_FILE, 4),
    VIDEO_V5(3, ".mp4", Encryption.SUFFIX_V5, 5),  // V5: No extension
    TEXT_V1(4, ".txt", Encryption.PREFIX_TEXT_FILE, 1),
    TEXT_V2(4, ".txt", Encryption.SUFFIX_TEXT_FILE, 2),
    TEXT_V3(4, ".txt", Encryption.SUFFIX_GENERIC_FILE, 3),
    TEXT_V4(4, ".txt", Encryption.SUFFIX_GENERIC_FILE, 4),
    TEXT_V5(4, ".txt", Encryption.SUFFIX_V5, 5);  // V5: No extension

    public static final int TYPE_DIRECTORY = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_GIF = 2;
    public static final int TYPE_VIDEO = 3;
    public static final int TYPE_TEXT = 4;

    public final String extension, suffixPrefix;
    public final int type, version;

    FileType(int type, String extension, String suffixPrefix, int version) {
        this.type = type;
        this.extension = extension;
        this.suffixPrefix = suffixPrefix;
        this.version = version;
    }

    public static FileType fromFilename(@NonNull String name) {
        if (name.startsWith(Encryption.PREFIX_IMAGE_FILE)) {
            return IMAGE_V1;
        } else if (name.endsWith(Encryption.SUFFIX_IMAGE_FILE)) {
            return IMAGE_V2;
        } else if (name.startsWith(Encryption.PREFIX_GIF_FILE)) {
            return GIF_V1;
        } else if (name.endsWith(Encryption.SUFFIX_GIF_FILE)) {
            return GIF_V2;
        } else if (name.startsWith(Encryption.PREFIX_VIDEO_FILE)) {
            return VIDEO_V1;
        } else if (name.endsWith(Encryption.SUFFIX_VIDEO_FILE)) {
            return VIDEO_V2;
        } else if (name.startsWith(Encryption.PREFIX_TEXT_FILE)) {
            return TEXT_V1;
        } else if (name.endsWith(Encryption.SUFFIX_TEXT_FILE)) {
            return TEXT_V2;
        } else if (name.endsWith(Encryption.SUFFIX_GENERIC_FILE) && !name.startsWith(Encryption.ENCRYPTED_PREFIX)) {
            // V3 files - all use .valv suffix, type must be determined from encrypted metadata
            // For now, return IMAGE_V3 as default (will be corrected when reading metadata)
            return IMAGE_V3;
        } else if (!name.contains(".") && name.matches("[a-zA-Z0-9]{32}")) {
            // V5 files - no extension, just 32-char alphanumeric random name
            // Type must be determined from encrypted metadata
            return IMAGE_V5;
        } else {
            return DIRECTORY;
        }
    }

    @NonNull
    public static FileType fromTypeAndVersion(int type, int version) {
        switch (type) {
            case TYPE_IMAGE:
                return version == 1 ? IMAGE_V1 : (version == 2 ? IMAGE_V2 : (version == 4 ? IMAGE_V4 : (version == 5 ? IMAGE_V5 : IMAGE_V3)));
            case TYPE_GIF:
                return version == 1 ? GIF_V1 : (version == 2 ? GIF_V2 : (version == 4 ? GIF_V4 : (version == 5 ? GIF_V5 : GIF_V3)));
            case TYPE_VIDEO:
                return version == 1 ? VIDEO_V1 : (version == 2 ? VIDEO_V2 : (version == 4 ? VIDEO_V4 : (version == 5 ? VIDEO_V5 : VIDEO_V3)));
            case TYPE_TEXT:
                return version == 1 ? TEXT_V1 : (version == 2 ? TEXT_V2 : (version == 4 ? TEXT_V4 : (version == 5 ? TEXT_V5 : TEXT_V3)));
            case TYPE_DIRECTORY:
            default:
                return DIRECTORY;
        }
    }

    public boolean isDirectory() {
        return this == DIRECTORY;
    }

    public boolean isImage() {
        return this == IMAGE_V1 || this == IMAGE_V2 || this == IMAGE_V3 || this == IMAGE_V4 || this == IMAGE_V5;
    }

    public boolean isGif() {
        return this == GIF_V1 || this == GIF_V2 || this == GIF_V3 || this == GIF_V4 || this == GIF_V5;
    }

    public boolean isVideo() {
        return this == VIDEO_V1 || this == VIDEO_V2 || this == VIDEO_V3 || this == VIDEO_V4 || this == VIDEO_V5;
    }

    public boolean isText() {
        return this == TEXT_V1 || this == TEXT_V2 || this == TEXT_V3 || this == TEXT_V4 || this == TEXT_V5;
    }
}
