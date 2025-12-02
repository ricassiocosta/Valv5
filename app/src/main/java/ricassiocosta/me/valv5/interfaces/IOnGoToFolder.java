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

package ricassiocosta.me.valv5.interfaces;

import ricassiocosta.me.valv5.data.GalleryFile;

/**
 * Callback interface for "Go to folder" action from the viewpager.
 * Used to navigate to the parent folder of a file when viewing from "All items".
 */
public interface IOnGoToFolder {
    /**
     * Called when the user wants to navigate to the folder containing the file.
     * @param galleryFile The file whose parent folder should be opened
     */
    void onGoToFolder(GalleryFile galleryFile);
}
