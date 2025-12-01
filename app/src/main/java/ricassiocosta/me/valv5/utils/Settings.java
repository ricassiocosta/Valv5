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

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.primitives.Bytes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import ricassiocosta.me.valv5.data.DirHash;
import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.data.StoredDirectory;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.interfaces.IOnDirectoryAdded;

public class Settings {
    private static final String TAG = "Settings";
    private static final String SHARED_PREFERENCES_NAME = "prefs";
    private static final String PREF_VAULT_PREFIX = "dirs_";
    private static final String PREF_VAULT_KEYS = "keys";
    private static final String PREF_SHOW_FILENAMES_IN_GRID = "p.gallery.fn";
    public static final String PREF_ENCRYPTION_ITERATION_COUNT = "encryption_iteration_count";
    public static final String PREF_ENCRYPTION_DELETE_BY_DEFAULT = "encryption_delete_by_default";
    public static final String PREF_ENCRYPTION_USE_ARGON2 = "encryption_use_argon2";

    public static final String PREF_APP_SECURE = "app_secure";
    public static final String PREF_APP_EDIT_FOLDERS = "app_edit_folders";
    public static final String PREF_APP_EXIT_ON_LOCK = "app_exit_on_lock";
    public static final String PREF_APP_RETURN_TO_LAST_APP = "app_return_to_last_app";
    public static final String PREF_APP_LAST_APP_PACKAGE = "app_last_app_package";
    public static final String PREF_APP_PREFERRED_APP = "app_preferred_app";
    public static final String PREF_APP_BIOMETRICS = "app_biometrics";
    public static final String PREF_APP_BIOMETRICS_DATA = "app_biometrics_data";

    private final Context context;
    private static Settings settings;

    public static Settings getInstance(@NonNull Context context) {
        if (settings == null) {
            settings = new Settings(context);
        }
        return settings;
    }

    private Settings(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public SharedPreferences getSharedPrefs() {
        return context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private SharedPreferences.Editor getSharedPrefsEditor() {
        return context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
    }

    public int getIterationCount() {
        return getSharedPrefs().getInt(PREF_ENCRYPTION_ITERATION_COUNT, 120000);
    }

    public void setIterationCount(int iterationCount) {
        getSharedPrefsEditor().putInt(PREF_ENCRYPTION_ITERATION_COUNT, iterationCount).apply();
    }

    /**
     * Check if Argon2id should be used for key derivation instead of PBKDF2.
     * Argon2id is more resistant to GPU/ASIC attacks but uses more memory.
     * Default: true (use Argon2id for new files)
     */
    public boolean useArgon2() {
        return getSharedPrefs().getBoolean(PREF_ENCRYPTION_USE_ARGON2, true);
    }

    /**
     * Set whether to use Argon2id for key derivation.
     */
    public void setUseArgon2(boolean useArgon2) {
        getSharedPrefsEditor().putBoolean(PREF_ENCRYPTION_USE_ARGON2, useArgon2).apply();
    }

    public boolean isDeleteByDefault() {
        return getSharedPrefs().getBoolean(PREF_ENCRYPTION_DELETE_BY_DEFAULT, false);
    }

    public boolean isBiometricsEnabled() {
        return getSharedPrefs().getString(PREF_APP_BIOMETRICS, null) != null;
    }

    public void setBiometricsEnabled(byte[] iv, byte[] data) {
        getSharedPrefsEditor()
                .putString(PREF_APP_BIOMETRICS, iv == null ? null : new String(iv, StandardCharsets.ISO_8859_1))
                .putString(PREF_APP_BIOMETRICS_DATA, data == null ? null : new String(data, StandardCharsets.ISO_8859_1))
                .apply();
    }

    public byte[] getBiometricsIv() {
        String s = getSharedPrefs().getString(PREF_APP_BIOMETRICS, null);
        if (s == null) {
            return null;
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    public byte[] getBiometricsData() {
        String s = getSharedPrefs().getString(PREF_APP_BIOMETRICS_DATA, null);
        if (s == null) {
            return null;
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    public void setDeleteByDefault(boolean deleteByDefault) {
        getSharedPrefsEditor().putBoolean(PREF_ENCRYPTION_DELETE_BY_DEFAULT, deleteByDefault).apply();
    }





    public boolean isSecureFlag() {
        return getSharedPrefs().getBoolean(PREF_APP_SECURE, true);
    }

    public void setSecureFlag(boolean secureFlag) {
        getSharedPrefsEditor().putBoolean(PREF_APP_SECURE, secureFlag).apply();
    }

    public boolean exitOnLock() {
        return getSharedPrefs().getBoolean(PREF_APP_EXIT_ON_LOCK, true);
    }

    public void setExitOnLock(boolean exitOnLock) {
        getSharedPrefsEditor().putBoolean(PREF_APP_EXIT_ON_LOCK, exitOnLock).apply();
    }

    public boolean returnToLastApp() {
        return getSharedPrefs().getBoolean(PREF_APP_RETURN_TO_LAST_APP, false);
    }

    public void setReturnToLastApp(boolean returnToLastApp) {
        getSharedPrefsEditor().putBoolean(PREF_APP_RETURN_TO_LAST_APP, returnToLastApp).apply();
    }

    public String getLastAppPackage() {
        return getSharedPrefs().getString(PREF_APP_LAST_APP_PACKAGE, null);
    }

    public void setLastAppPackage(String packageName) {
        getSharedPrefsEditor().putString(PREF_APP_LAST_APP_PACKAGE, packageName).apply();
    }

    public String getPreferredApp() {
        return getSharedPrefs().getString(PREF_APP_PREFERRED_APP, null);
    }

    public void setPreferredApp(String packageName) {
        getSharedPrefsEditor().putString(PREF_APP_PREFERRED_APP, packageName).apply();
    }

    public void addGalleryDirectory(@NonNull Uri uri, boolean asRootDir, @Nullable IOnDirectoryAdded onDirectoryAdded) {
        List<StoredDirectory> directories = getGalleryDirectories(false);
        StoredDirectory newDir = new StoredDirectory(uri, asRootDir);
        boolean reordered = false;
        if (directories.contains(newDir)) {
            if (directories.remove(newDir)) {
                directories.add(0, newDir);
                reordered = true;
            }
        } else {
            directories.add(0, newDir);
        }
        getSharedPrefsEditor().putString(getDirsKey(), stringListAsString(directories)).apply();
        if (onDirectoryAdded != null) {
            if (reordered) {
                onDirectoryAdded.onAlreadyExists();
            } else if (asRootDir) {
                onDirectoryAdded.onAddedAsRoot();
            } else {
                onDirectoryAdded.onAdded();
            }
        }
    }

    public void removeGalleryDirectory(@NonNull Uri uri) {
        List<StoredDirectory> directories = getGalleryDirectories(false);
        String[] split = uri.toString().split("/document/");
        directories.remove(new StoredDirectory(split[0], false));
        directories.remove(new StoredDirectory(uri, false));
        getSharedPrefsEditor().putString(getDirsKey(), stringListAsString(directories)).apply();
    }

    public void removeGalleryDirectories(@NonNull List<Uri> uris) {
        List<StoredDirectory> directories = getGalleryDirectories(false);
        for (Uri u : uris) {
            directories.remove(new StoredDirectory(u, false));
        }
        getSharedPrefsEditor().putString(getDirsKey(), stringListAsString(directories)).apply();
    }

    @NonNull
    private String stringListAsString(@NonNull List<StoredDirectory> list) {
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator<StoredDirectory> iterator = list.iterator();
        while (iterator.hasNext()) {
            sb.append(iterator.next().toString());
            if (iterator.hasNext()) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @NonNull
    public List<Uri> getGalleryDirectoriesAsUri(boolean rootDirsOnly) {
        List<StoredDirectory> directories = getGalleryDirectories(rootDirsOnly);
        List<Uri> uris = new ArrayList<>(directories.size());
        for (StoredDirectory s : directories) {
            if (s != null) {
                uris.add(s.getUri());
            }
        }
        return uris;
    }

    @NonNull
    private List<StoredDirectory> getGalleryDirectories(boolean rootDirsOnly) {
        String s = getSharedPrefs().getString(getDirsKey(), null);
        List<StoredDirectory> storedDirectories = new ArrayList<>();
        if (s != null && !s.isEmpty()) {
            String[] split = s.split("\n");
            for (String value : split) {
                if (value != null && !value.isEmpty()) {
                    boolean isRootDir = value.charAt(0) == '1';
                    if (!rootDirsOnly || isRootDir) {
                        storedDirectories.add(new StoredDirectory(value.substring(1), isRootDir));
                    }
                }
            }
        }
        return storedDirectories;
    }

    private String getDirsKey() {
        return PREF_VAULT_PREFIX + new String(Password.getInstance().getDirHash().hash(), StandardCharsets.UTF_8);
    }

    public DirHash getDirHashForKey(char[] password) {
        String keys = getSharedPrefs().getString(PREF_VAULT_KEYS, "");
        byte[] bytes = keys.getBytes(StandardCharsets.ISO_8859_1);

        final int entryLength = Encryption.SALT_LENGTH + Encryption.DIR_HASH_LENGTH; // 16 + 8

        int startPos = 0;
        while (startPos + entryLength <= bytes.length) { // for each entry, check if the current password can produce the same hash
            byte[] salt = Arrays.copyOfRange(bytes, startPos, startPos + Encryption.SALT_LENGTH); // 16 bytes
            byte[] hash = Arrays.copyOfRange(bytes, startPos + Encryption.SALT_LENGTH, startPos + Encryption.SALT_LENGTH + Encryption.DIR_HASH_LENGTH); // following 8 bytes
            DirHash dirHash = Encryption.getDirHash(salt, password);

            if (Arrays.equals(dirHash.hash(), hash)) {
                return new DirHash(salt, dirHash.hash());
            }
            startPos += entryLength;
        }
        return null;
    }

    public void createDirHashEntry(byte[] salt, byte[] hash) {
        String keys = getSharedPrefs().getString(PREF_VAULT_KEYS, "");
        String newKeys = new String(Bytes.concat(keys.getBytes(StandardCharsets.ISO_8859_1), salt, hash), StandardCharsets.ISO_8859_1);
        getSharedPrefsEditor()
                .putString(PREF_VAULT_KEYS, newKeys)
                .apply();
    }

    public void deleteDirHashEntry(byte[] salt, byte[] hash) {
        if (salt == null || hash == null) {
            return;
        }

        byte[] bytesToRemove = Bytes.concat(salt, hash);
        byte[] storedBytes = getSharedPrefs().getString(PREF_VAULT_KEYS, "").getBytes(StandardCharsets.ISO_8859_1);

        if (storedBytes.length < bytesToRemove.length) {
            return;
        }

        final int entryLength = Encryption.SALT_LENGTH + Encryption.DIR_HASH_LENGTH;

        for (int i = 0; i + entryLength <= storedBytes.length; i += entryLength) {
            byte[] bytesToCheck = Arrays.copyOfRange(storedBytes, i, i + entryLength);
            if (Arrays.equals(bytesToRemove, bytesToCheck)) {
                byte[] before = i == 0 ? new byte[0] : Arrays.copyOfRange(storedBytes, 0, i);
                byte[] after = i + entryLength > storedBytes.length ? new byte[0] : Arrays.copyOfRange(storedBytes, i + entryLength, storedBytes.length);

                String newKeys = new String(Bytes.concat(before, after), StandardCharsets.ISO_8859_1);
                getSharedPrefsEditor()
                        .putString(PREF_VAULT_KEYS, newKeys)
                        .remove(PREF_VAULT_PREFIX + new String(hash, StandardCharsets.UTF_8))
                        .apply();

                break;
            }
        }
    }

    public void setShowFilenames(boolean show) {
        getSharedPrefsEditor().putBoolean(PREF_SHOW_FILENAMES_IN_GRID, show).apply();
    }

    public boolean showFilenames() {
        return getSharedPrefs().getBoolean(PREF_SHOW_FILENAMES_IN_GRID, true);
    }
}
