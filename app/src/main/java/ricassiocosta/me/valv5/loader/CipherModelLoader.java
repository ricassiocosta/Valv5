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

package ricassiocosta.me.valv5.loader;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.signature.ObjectKey;

import java.io.InputStream;

import ricassiocosta.me.valv5.encryption.Encryption;

public class CipherModelLoader implements ModelLoader<Uri, InputStream> {
    private final Context context;
    private final int version;

    public CipherModelLoader(@NonNull Context context, int version) {
        this.context = context.getApplicationContext();
        this.version = version;
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull Uri uri, int width, int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey(uri), new CipherDataFetcher(context, uri, version));
    }

    @Override
    public boolean handles(@NonNull Uri uri) {
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment == null) {
            return false;
        }
        
        // V5 only: no extension, just 32-char alphanumeric random name
        return !lastSegment.contains(".") && lastSegment.matches("[a-zA-Z0-9]{32}");
    }

}
