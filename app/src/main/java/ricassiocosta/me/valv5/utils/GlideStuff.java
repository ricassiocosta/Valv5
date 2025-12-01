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

import ricassiocosta.me.valv5.security.EphemeralSessionKey;

public class GlideStuff {

    /**
     * Request options for viewing images - no disk cache for security.
     * Uses ephemeral session key for cache signature to ensure cache isolation between sessions.
     */
    @NonNull
    public static RequestOptions getRequestOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .signature(new ObjectKey(EphemeralSessionKey.getInstance().getSessionId()));
    }

    /**
     * Request options for grid thumbnails - skips both disk and memory cache.
     * Thumbnails will be reloaded when scrolling back, but this prevents OOM with large galleries
     * and ensures no decrypted data is written to disk.
     * Uses ephemeral session key for additional security.
     */
    @NonNull
    public static RequestOptions getGridThumbnailOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .signature(new ObjectKey(EphemeralSessionKey.getInstance().getSessionId()))
                .skipMemoryCache(true);
    }

}
