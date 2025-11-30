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
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

import ricassiocosta.me.valv5.data.FileType;
import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;

public class CipherDataFetcher implements DataFetcher<InputStream> {
    private static final String TAG = "CipherDataFetcher";
    private Encryption.Streams streams;
    private final Context context;
    private final Uri uri;
    private final int version;
    private final Password password;

    public CipherDataFetcher(@NonNull Context context, Uri uri, int version) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.version = version;
        this.password = Password.getInstance();
    }

    @Override
    public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
        android.util.Log.e(TAG, "===== CipherDataFetcher.loadData START =====");
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            
            // For V5, always load FILE, not thumbnail
            boolean loadThumbnail = false;  // V5 only: always load FILE section
            
            android.util.Log.e(TAG, "loadData: uri=" + uri + ", version=" + version + ", loadThumbnail=" + loadThumbnail);
            
            streams = Encryption.getCipherInputStream(
                    inputStream,
                    password.getPassword(),
                    loadThumbnail,
                    version
            );
            android.util.Log.e(TAG, "loadData: streams created, compositeStreams=" + (streams.compositeStreams != null));
            
            // For V5 (detected by compositeStreams presence), read file content into memory
            // This prevents the stream from being closed while Glide is decoding
            if (streams.compositeStreams != null) {
                android.util.Log.e(TAG, "loadData: V5 file, calling getFileBytes()");
                byte[] fileBytes = streams.getFileBytes();
                android.util.Log.e(TAG, "loadData: fileBytes=" + (fileBytes != null ? fileBytes.length + " bytes" : "null"));
                if (fileBytes != null) {
                    android.util.Log.e(TAG, "loadData: Creating ByteArrayInputStream and calling onDataReady");
                    callback.onDataReady(new ByteArrayInputStream(fileBytes));
                } else {
                    android.util.Log.e(TAG, "loadData: Failed to read V5 file bytes");
                    callback.onLoadFailed(new IOException("Failed to read V5 file bytes"));
                }
            } else {
                // For V1-V4, return the stream directly
                android.util.Log.e(TAG, "loadData: V1-V4 file, using getInputStream()");
                InputStream data = streams.getInputStream();
                if (data != null) {
                    android.util.Log.e(TAG, "loadData: Got InputStream, calling onDataReady");
                    callback.onDataReady(data);
                } else {
                    android.util.Log.e(TAG, "loadData: Failed to get input stream");
                    callback.onLoadFailed(new IOException("Failed to get input stream"));
                }
            }
            android.util.Log.e(TAG, "===== CipherDataFetcher.loadData END (SUCCESS) =====");
        } catch (GeneralSecurityException | IOException | InvalidPasswordException |
                 JSONException e) {
            android.util.Log.e(TAG, "===== CipherDataFetcher.loadData END (EXCEPTION) =====", e);
            //e.printStackTrace();
            callback.onLoadFailed(e);
        }
    }

    @Override
    public void cleanup() {
        cancel();
    }

    @Override
    public void cancel() {
        if (streams != null) {
            streams.close(); // interrupts decode if any
        }
    }

    @NonNull
    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @NonNull
    @Override
    public DataSource getDataSource() {
        return DataSource.LOCAL;
    }
}
