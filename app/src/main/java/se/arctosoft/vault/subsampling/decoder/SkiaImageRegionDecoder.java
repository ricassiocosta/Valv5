/*
 * Valv-Android
 * Copyright (c) 2024 Arctosoft AB.
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
 * You should have received a copy of the GNU General Public License along with this program.  If not, see https://www.gnu.org/licenses/.
 */

package se.arctosoft.vault.subsampling.decoder;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import se.arctosoft.vault.encryption.Encryption;
import se.arctosoft.vault.subsampling.MySubsamplingScaleImageView;

/**
 * Default implementation of {@link com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder}
 * using Android's {@link android.graphics.BitmapRegionDecoder}, based on the Skia library. This
 * works well in most circumstances and has reasonable performance due to the cached decoder instance,
 * however it has some problems with grayscale, indexed and CMYK images.
 * <p>
 * A {@link ReadWriteLock} is used to delegate responsibility for multi threading behaviour to the
 * {@link BitmapRegionDecoder} instance on SDK &gt;= 21, whilst allowing this class to block until no
 * tiles are being loaded before recycling the decoder. In practice, {@link BitmapRegionDecoder} is
 * synchronized internally so this has no real impact on performance.
 */
public class SkiaImageRegionDecoder implements ImageRegionDecoder {

    private BitmapRegionDecoder decoder;
    private final ReadWriteLock decoderLock = new ReentrantReadWriteLock(true);

    private final Bitmap.Config bitmapConfig;

    @Keep
    @SuppressWarnings("unused")
    public SkiaImageRegionDecoder() {
        this(null);
    }

    @SuppressWarnings({"WeakerAccess", "SameParameterValue"})
    public SkiaImageRegionDecoder(@Nullable Bitmap.Config bitmapConfig) {
        Bitmap.Config globalBitmapConfig = MySubsamplingScaleImageView.getPreferredBitmapConfig();
        if (bitmapConfig != null) {
            this.bitmapConfig = bitmapConfig;
        } else if (globalBitmapConfig != null) {
            this.bitmapConfig = globalBitmapConfig;
        } else {
            this.bitmapConfig = Bitmap.Config.RGB_565;
        }
    }

    @Override
    @NonNull
    public Point init(Context context, @NonNull Uri uri, char[] password, int version) throws Exception {
        Encryption.Streams streams = null;
        try {
            ContentResolver contentResolver = context.getContentResolver();
            streams = Encryption.getCipherInputStream(contentResolver.openInputStream(uri), password, false, version);
            Log.d("SkiaImageRegionDecoder", "init: streams created, compositeStreams=" + (streams.compositeStreams != null));
            
            // For V5 (detected by compositeStreams presence), use getFileBytes() 
            // to get the complete file content.
            // BitmapRegionDecoder needs a seekable stream that stays open during decoding.
            InputStream decoderInput;
            if (streams.compositeStreams != null) {
                Log.d("SkiaImageRegionDecoder", "init: V5 file detected, calling getFileBytes()");
                byte[] fileBytes = streams.getFileBytes();
                Log.d("SkiaImageRegionDecoder", "init: fileBytes=" + (fileBytes != null ? fileBytes.length + " bytes" : "null"));
                if (fileBytes != null) {
                    decoderInput = new ByteArrayInputStream(fileBytes);
                    Log.d("SkiaImageRegionDecoder", "init: created ByteArrayInputStream for " + fileBytes.length + " bytes");
                } else {
                    Log.e("SkiaImageRegionDecoder", "init: Failed to read V5 file bytes!");
                    throw new IOException("Failed to read V5 file bytes for region decoder");
                }
            } else {
                Log.d("SkiaImageRegionDecoder", "init: V1-V4 file, using getInputStream()");
                decoderInput = streams.getInputStream();
            }
            
            Log.d("SkiaImageRegionDecoder", "init: calling BitmapRegionDecoder.newInstance()");
            decoder = BitmapRegionDecoder.newInstance(decoderInput, false);
            Log.d("SkiaImageRegionDecoder", "init: decoder created successfully, size=" + decoder.getWidth() + "x" + decoder.getHeight());
        } catch (Exception e) {
            Log.e("SkiaImageRegionDecoder", "init: Exception occurred", e);
            throw e;
        } finally {
            if (streams != null) {
                // Don't close streams here for V5, as the ByteArrayInputStream doesn't need it
                // For V1-V4, the stream might be needed by BitmapRegionDecoder
                // Actually, it's safe to close as ByteArrayInputStream is already in memory
                streams.close();
            }
        }
        return new Point(decoder.getWidth(), decoder.getHeight());
    }

    @Override
    @NonNull
    public Bitmap decodeRegion(@NonNull Rect sRect, int sampleSize) {
        getDecodeLock().lock();
        try {
            if (decoder != null && !decoder.isRecycled()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                options.inPreferredConfig = bitmapConfig;
                Bitmap bitmap = decoder.decodeRegion(sRect, options);
                if (bitmap == null) {
                    throw new RuntimeException("Skia image decoder returned null bitmap - image format may not be supported");
                }
                return bitmap;
            } else {
                throw new IllegalStateException("Cannot decode region after decoder has been recycled");
            }
        } finally {
            getDecodeLock().unlock();
        }
    }

    @Override
    public synchronized boolean isReady() {
        return decoder != null && !decoder.isRecycled();
    }

    @Override
    public synchronized void recycle() {
        decoderLock.writeLock().lock();
        try {
            decoder.recycle();
            decoder = null;
        } finally {
            decoderLock.writeLock().unlock();
        }
    }

    /**
     * Before SDK 21, BitmapRegionDecoder was not synchronized internally. Any attempt to decode
     * regions from multiple threads with one decoder instance causes a segfault. For old versions
     * use the write lock to enforce single threaded decoding.
     */
    private Lock getDecodeLock() {
        return decoderLock.readLock();
    }
}
