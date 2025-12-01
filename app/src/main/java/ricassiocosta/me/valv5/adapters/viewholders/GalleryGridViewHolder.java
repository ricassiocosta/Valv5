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

package ricassiocosta.me.valv5.adapters.viewholders;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import ricassiocosta.me.valv5.databinding.AdapterGalleryGridItemBinding;

public class GalleryGridViewHolder extends RecyclerView.ViewHolder {
    public final AdapterGalleryGridItemBinding binding;
    
    // Track the current file URI for this ViewHolder to support task cancellation
    @Nullable
    private Uri currentFileUri;

    public GalleryGridViewHolder(@NonNull AdapterGalleryGridItemBinding binding) {
        super(binding.getRoot());
        this.binding = binding;
    }
    
    public void setCurrentFileUri(@Nullable Uri uri) {
        this.currentFileUri = uri;
    }
    
    @Nullable
    public Uri getCurrentFileUri() {
        return currentFileUri;
    }
}
