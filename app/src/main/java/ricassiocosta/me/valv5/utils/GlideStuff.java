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

package ricassiocosta.me.valv5.utils;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;

import ricassiocosta.me.valv5.MainActivity;

public class GlideStuff {

    @NonNull
    public static RequestOptions getRequestOptions(boolean useDiskCache) {
        return new RequestOptions()
                .diskCacheStrategy(useDiskCache ? DiskCacheStrategy.AUTOMATIC : DiskCacheStrategy.NONE)
                .signature(new ObjectKey(MainActivity.GLIDE_KEY));
    }

    /**
     * Request options for grid thumbnails - skips memory cache to reduce memory usage.
     * Thumbnails will be reloaded when scrolling back, but this prevents OOM with large galleries.
     */
    @NonNull
    public static RequestOptions getGridThumbnailOptions(boolean useDiskCache) {
        return new RequestOptions()
                .diskCacheStrategy(useDiskCache ? DiskCacheStrategy.AUTOMATIC : DiskCacheStrategy.NONE)
                .signature(new ObjectKey(MainActivity.GLIDE_KEY))
                .skipMemoryCache(true);
    }

}
