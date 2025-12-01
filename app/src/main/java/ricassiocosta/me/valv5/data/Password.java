/*
 * Valv-Android
 * Copyright (C) 2023 Arctosoft AB
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

package ricassiocosta.me.valv5.data;

import android.content.Context;

import com.bumptech.glide.Glide;

import java.util.Arrays;

import ricassiocosta.me.valv5.security.EphemeralSessionKey;
import ricassiocosta.me.valv5.security.SecureMemoryManager;
import ricassiocosta.me.valv5.utils.FileStuff;
import ricassiocosta.me.valv5.utils.Settings;

public class Password {
    private static final String TAG = "Password";

    private static Password instance;
    private char[] password;
    private DirHash dirHash;

    private Password() {
    }

    public void setPassword(char[] password) {
        // Register the new password with SecureMemoryManager for cleanup
        if (password != null) {
            SecureMemoryManager.getInstance().register(password);
        }
        this.password = password;
    }

    public void setDirHash(DirHash dirHash) {
        this.dirHash = dirHash;
    }

    public DirHash getDirHash() {
        return dirHash;
    }

    public char[] getPassword() {
        return password;
    }

    public static Password getInstance() {
        if (instance == null) {
            instance = new Password();
        }
        return instance;
    }

    public void clear() {
        if (password != null) {
            // Use SecureMemoryManager for secure wiping
            SecureMemoryManager.getInstance().wipeNow(password);
            password = null;
        }
        if (dirHash != null) {
            dirHash.clear();
            dirHash = null;
        }
    }

    public static void lock(Context context, boolean deleteDirHash) {
        Password p = Password.getInstance();
        if (deleteDirHash && context != null && p.dirHash != null) {
            Settings settings = Settings.getInstance(context);
            settings.deleteDirHashEntry(p.dirHash.salt(), p.dirHash.hash());
        }
        p.clear();
        
        // Destroy ephemeral session key - invalidates all cached data
        EphemeralSessionKey.getInstance().destroy();
        
        // Perform full memory cleanup including all registered sensitive buffers
        SecureMemoryManager.getInstance().performFullCleanup(context);
        
        // Legacy cleanup for backwards compatibility
        if (context != null) {
            FileStuff.deleteCache(context);
            Glide.get(context).clearMemory();
        }
    }
}
