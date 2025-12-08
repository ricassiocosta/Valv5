/*
 * Valv5
 * Copyright (c) 2024 Arctosoft AB.
 * Copyright (c) 2025 ricassiocosta.
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

import androidx.annotation.NonNull;

import ricassiocosta.me.valv5.encryption.Encryption;

public enum FileType {
    DIRECTORY(0, null, null, 5),  // V5 only
    IMAGE_V5(1, ".jpg", Encryption.SUFFIX_V5, 5),
    GIF_V5(2, ".gif", Encryption.SUFFIX_V5, 5),
    VIDEO_V5(3, ".mp4", Encryption.SUFFIX_V5, 5),
    TEXT_V5(4, ".txt", Encryption.SUFFIX_V5, 5);

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

    @NonNull
    public static FileType fromTypeAndVersion(int type, int version) {
        // V5 only
        switch (type) {
            case TYPE_IMAGE:
                return IMAGE_V5;
            case TYPE_GIF:
                return GIF_V5;
            case TYPE_VIDEO:
                return VIDEO_V5;
            case TYPE_TEXT:
                return TEXT_V5;
            case TYPE_DIRECTORY:
            default:
                return DIRECTORY;
        }
    }

    public boolean isDirectory() {
        return this == DIRECTORY;
    }

    public boolean isImage() {
        return this == IMAGE_V5;
    }

    public boolean isGif() {
        return this == GIF_V5;
    }

    public boolean isVideo() {
        return this == VIDEO_V5;
    }

    public boolean isText() {
        return this == TEXT_V5;
    }
}
