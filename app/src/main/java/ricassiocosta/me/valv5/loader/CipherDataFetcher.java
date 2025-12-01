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

import ricassiocosta.me.valv5.security.SecureLog;

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
        SecureLog.d(TAG, "loadData: START");
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            // For V5, always load FILE, not thumbnail
            boolean loadThumbnail = false;
            streams = Encryption.getCipherInputStream(
                    inputStream,
                    password.getPassword(),
                    loadThumbnail,
                    version
            );
            SecureLog.d(TAG, "loadData: streams created, compositeStreams=" + (streams.compositeStreams != null));
            SecureLog.d(TAG, "loadData: V5 file, calling getFileBytes()");
            byte[] fileBytes = streams.getFileBytes();
            SecureLog.d(TAG, "loadData: " + SecureLog.redactBytes(fileBytes));
            if (fileBytes != null) {
                SecureLog.d(TAG, "loadData: Creating ByteArrayInputStream");
                callback.onDataReady(new ByteArrayInputStream(fileBytes));
            } else {
                SecureLog.d(TAG, "loadData: Failed to read V5 file bytes");
                callback.onLoadFailed(new IOException("Failed to read V5 file bytes"));
            }
            SecureLog.d(TAG, "loadData: END (SUCCESS)");
        } catch (GeneralSecurityException | IOException | InvalidPasswordException |
                 JSONException e) {
            SecureLog.d(TAG, "loadData: END (EXCEPTION)", e);
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
