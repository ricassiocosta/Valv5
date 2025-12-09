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
        // Note: The password is intentionally NOT registered with SecureMemoryManager here
        // because it must persist during the entire session for folder decryption and file operations.
        // Security trade-off: The password remains in memory while the app is backgrounded.
        // However, it is securely wiped in clear() when the vault is explicitly locked.
        // If stronger security is needed (wipe on background), consider implementing a
        // "persistent buffer" mechanism in SecureMemoryManager that excludes from partial cleanup.
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
        
        // Clear encrypted folder name cache
        ricassiocosta.me.valv5.encryption.FolderNameCache.getInstance().clear();
        
        // Clear index cache
        ricassiocosta.me.valv5.index.IndexManager.getInstance().clear();
        
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
