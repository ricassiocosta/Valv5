/*
 * Valv-Android
 * Copyright (C) 2024 Arctosoft AB
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

package ricassiocosta.me.valv5.loader;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.signature.ObjectKey;

import java.io.InputStream;

import ricassiocosta.me.valv5.data.EncryptedFile;

public class EncryptedFileModelLoader implements ModelLoader<EncryptedFile, InputStream> {
    private final Context context;

    public EncryptedFileModelLoader(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull EncryptedFile encryptedFile, int width, int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey(encryptedFile.getUri()), new CipherDataFetcher(context, encryptedFile.getUri(), encryptedFile.getVersion()));
    }

    @Override
    public boolean handles(@NonNull EncryptedFile encryptedFile) {
        return true;
    }
}
