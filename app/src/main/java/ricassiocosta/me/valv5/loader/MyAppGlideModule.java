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
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.io.InputStream;

import ricassiocosta.me.valv5.data.EncryptedFile;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.drawable.DrawableResource;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.load.Encoder;

@GlideModule
public class MyAppGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.prepend(EncryptedFile.class, InputStream.class, new EncryptedFileModelLoaderFactory(context));
        
        // Prevent any encrypted/decrypted data from being written to disk cache
        // This ensures security by rejecting ALL disk write attempts
        registry.prepend(Bitmap.class, new Encoder<Bitmap>() {
            @Override
            public boolean encode(@NonNull Bitmap data, @NonNull File file, @NonNull Options options) {
                return false; // Reject all disk writes
            }
        });
        
        registry.prepend(Drawable.class, new Encoder<Drawable>() {
            @Override
            public boolean encode(@NonNull Drawable data, @NonNull File file, @NonNull Options options) {
                return false; // Reject all disk writes
            }
        });
    }

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setLogLevel(Log.ERROR);
        // Disable disk cache globally for security - no decrypted data should be written to disk
        // Also skip memory cache to ensure no cached data persists
        builder.setDefaultRequestOptions(
                new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
        );
        super.applyOptions(context, builder);
    }
}