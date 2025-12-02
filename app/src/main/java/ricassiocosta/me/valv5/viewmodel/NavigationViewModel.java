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

package ricassiocosta.me.valv5.viewmodel;

import android.net.Uri;

import androidx.lifecycle.ViewModel;

/**
 * Activity-scoped ViewModel for sharing navigation state between fragments.
 * This avoids passing sensitive URIs through Bundle serialization.
 * 
 * Data stored here is:
 * - Only kept in memory (not persisted)
 * - Cleared when the Activity is destroyed
 * - Not exposed to other apps
 */
public class NavigationViewModel extends ViewModel {
    
    // URI of file to scroll to after navigating to a folder
    private Uri pendingScrollToFileUri;
    
    /**
     * Set the URI of a file to scroll to after navigation.
     * This should be consumed (cleared) after use.
     */
    public void setPendingScrollToFileUri(Uri uri) {
        this.pendingScrollToFileUri = uri;
    }
    
    /**
     * Get and clear the pending scroll-to-file URI.
     * Returns null if no pending scroll is set.
     */
    public Uri consumePendingScrollToFileUri() {
        Uri uri = this.pendingScrollToFileUri;
        this.pendingScrollToFileUri = null;
        return uri;
    }
    
    /**
     * Check if there's a pending scroll-to-file request.
     */
    public boolean hasPendingScrollToFile() {
        return pendingScrollToFileUri != null;
    }
    
    @Override
    protected void onCleared() {
        super.onCleared();
        // Clear any pending navigation state
        pendingScrollToFileUri = null;
    }
}
