/*
 * Valv5
 * Copyright (c) 2024 Arctosoft AB.
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

package ricassiocosta.me.valv5.encryption;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

import javax.crypto.CipherInputStream;

import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.security.SecureLog;

@OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MyDataSource implements DataSource {
    private static final String TAG = "MyDataSource";
    private final Context context;

    private Encryption.Streams streams;
    private InputStream cachedInputStream;  // Cache the input stream for reuse
    private Uri uri;
    private final Password password;
    private final int version;

    public MyDataSource(@NonNull Context context, int version, Password password) {
        this.context = context.getApplicationContext();
        this.version = version;
        this.password = password;
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        try {
            InputStream fileStream = context.getContentResolver().openInputStream(uri);
            streams = Encryption.getCipherInputStream(fileStream, password.getPassword(), false, version);
        } catch (GeneralSecurityException | InvalidPasswordException | JSONException e) {
            e.printStackTrace();
            SecureLog.e(TAG, "open error", e);
            return 0;
        }

        // Use streaming for V5 to avoid loading entire file into memory
        cachedInputStream = streams.getInputStreamStreaming();
        if (cachedInputStream == null) {
            SecureLog.e(TAG, "open: inputStream is null!");
            return 0;
        }

        if (dataSpec.position != 0) {
            long skipped = forceSkip(dataSpec.position, cachedInputStream);
        }
        return dataSpec.length;
    }

    private long forceSkip(long skipBytes, InputStream inputStream) throws IOException {
        long skipped = 0L;
        while (skipped < skipBytes) {
            int read = inputStream.read();
            if (read == -1) {
                SecureLog.w(TAG, "forceSkip: EOF reached after " + skipped + " bytes");
                break;
            }
            skipped++;
        }
        return skipped;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }

        // Use the cached input stream instead of calling getInputStream() each time
        if (cachedInputStream == null) {
            SecureLog.e(TAG, "read: cachedInputStream is null!");
            return -1;
        }
        int bytesRead = cachedInputStream.read(buffer, offset, length);
        return bytesRead;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        if (streams != null) {
            streams.close();
        }
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
    }

}
