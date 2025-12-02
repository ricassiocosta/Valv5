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

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;

import ricassiocosta.me.valv5.security.EphemeralSessionKey;

public class GlideStuff {
    
    // Placeholder drawable to prevent flickering during image load
    private static final ColorDrawable PLACEHOLDER = new ColorDrawable(Color.TRANSPARENT);

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
     * Request options for grid thumbnails - uses memory cache but skips disk cache.
     * Memory cache is safe because it's cleared when the app locks.
     * Placeholder prevents flickering during scroll.
     * Uses ephemeral session key for additional security.
     */
    @NonNull
    public static RequestOptions getGridThumbnailOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .signature(new ObjectKey(EphemeralSessionKey.getInstance().getSessionId()))
                .placeholder(PLACEHOLDER)
                .skipMemoryCache(false);  // Allow memory cache for smoother scrolling
    }

}
