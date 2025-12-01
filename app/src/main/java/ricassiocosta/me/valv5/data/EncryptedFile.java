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

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.Objects;

public class EncryptedFile {
    private final Uri uri;
    private final int version;

    public EncryptedFile(@NonNull Uri uri, int version) {
        this.uri = uri;
        this.version = version;
    }

    @NonNull
    public Uri getUri() {
        return uri;
    }

    public int getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EncryptedFile that = (EncryptedFile) o;
        return version == that.version && uri.equals(that.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, version);
    }
}
