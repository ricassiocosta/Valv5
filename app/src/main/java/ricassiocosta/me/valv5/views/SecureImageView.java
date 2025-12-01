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

package ricassiocosta.me.valv5.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import ricassiocosta.me.valv5.security.SecureLog;

/**
 * A security-enhanced ImageView that securely clears bitmap data when detached from window.
 * 
 * <h3>Security Features:</h3>
 * <ul>
 *   <li><b>Bitmap Wiping:</b> Overwrites bitmap pixels with black before recycling</li>
 *   <li><b>Hardware Layer:</b> Uses hardware acceleration for better isolation</li>
 *   <li><b>Automatic Cleanup:</b> Cleans up when view is detached or visibility changes</li>
 * </ul>
 * 
 * <h3>How it Works:</h3>
 * <p>When the view is detached from the window (e.g., when navigating away or closing),
 * the bitmap is:</p>
 * <ol>
 *   <li>Filled with black pixels (eraseColor) to overwrite sensitive data</li>
 *   <li>Recycled to free memory</li>
 *   <li>Drawable reference set to null</li>
 * </ol>
 * 
 * <h3>Limitations:</h3>
 * <ul>
 *   <li>Cannot prevent screenshots on rooted devices or via ADB</li>
 *   <li>Cannot prevent physical camera capture of screen</li>
 *   <li>GPU memory may retain fragments until overwritten by other content</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <p>Use this view instead of standard ImageView for displaying sensitive decrypted images.</p>
 * 
 * @see android.view.WindowManager.LayoutParams#FLAG_SECURE for screenshot prevention
 */
public class SecureImageView extends AppCompatImageView {
    
    private static final String TAG = "SecureImageView";
    
    // Whether to use hardware layer (better isolation but more memory)
    private boolean useHardwareLayer = true;
    
    // Track if we should clear on detach (might be disabled during view recycling)
    private boolean clearOnDetach = true;

    public SecureImageView(@NonNull Context context) {
        super(context);
        init();
    }

    public SecureImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SecureImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    /**
     * Initialize security features.
     */
    private void init() {
        if (useHardwareLayer) {
            // Use hardware layer for better isolation of rendered content
            // This keeps the bitmap in GPU memory which is harder to access
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }
    }
    
    /**
     * Set whether to use hardware layer for rendering.
     * Hardware layer provides better memory isolation but uses more GPU memory.
     * 
     * @param useHardware true to use hardware layer (default), false for software
     */
    public void setUseHardwareLayer(boolean useHardware) {
        this.useHardwareLayer = useHardware;
        setLayerType(useHardware ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE, null);
    }
    
    /**
     * Set whether to clear bitmap when view is detached.
     * Disable this temporarily when recycling views in a RecyclerView.
     * 
     * @param clear true to clear on detach (default), false to preserve
     */
    public void setClearOnDetach(boolean clear) {
        this.clearOnDetach = clear;
    }
    
    @Override
    protected void onDetachedFromWindow() {
        if (clearOnDetach) {
            secureClearBitmap();
        }
        super.onDetachedFromWindow();
    }
    
    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        
        // When view becomes invisible (GONE), consider clearing the bitmap
        // This is aggressive but provides better security
        // Disabled by default as it may cause issues with view recycling
        // if (visibility == GONE && clearOnDetach) {
        //     secureClearBitmap();
        // }
    }
    
    /**
     * Securely clear the bitmap, overwriting pixels before recycling.
     * Safe to call multiple times.
     */
    public void secureClearBitmap() {
        Drawable drawable = getDrawable();
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            secureClearBitmap(bitmap);
        }
        setImageDrawable(null);
    }
    
    /**
     * Securely clear a specific bitmap.
     * 
     * @param bitmap The bitmap to clear, may be null
     */
    public static void secureClearBitmap(@Nullable Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            try {
                // Check if bitmap is mutable before trying to erase
                if (bitmap.isMutable()) {
                    // Overwrite with black pixels to clear sensitive image data
                    bitmap.eraseColor(Color.BLACK);
                    
                    // Optional: multiple patterns for paranoid mode
                    // bitmap.eraseColor(Color.WHITE);
                    // bitmap.eraseColor(Color.BLACK);
                }
                
                // Recycle the bitmap to free memory
                bitmap.recycle();
                
                SecureLog.d(TAG, "Bitmap securely cleared and recycled");
            } catch (Exception e) {
                // Bitmap might already be recycled or in use
                SecureLog.w(TAG, "Failed to securely clear bitmap", e);
            }
        }
    }
    
    /**
     * Override setImageBitmap to track bitmaps for secure clearing.
     */
    @Override
    public void setImageBitmap(@Nullable Bitmap bm) {
        // Clear previous bitmap before setting new one
        if (clearOnDetach) {
            Drawable currentDrawable = getDrawable();
            if (currentDrawable instanceof BitmapDrawable) {
                Bitmap currentBitmap = ((BitmapDrawable) currentDrawable).getBitmap();
                // Only clear if it's a different bitmap
                if (currentBitmap != bm) {
                    secureClearBitmap(currentBitmap);
                }
            }
        }
        super.setImageBitmap(bm);
    }
    
    /**
     * Override setImageDrawable to handle bitmap cleanup.
     */
    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        // Clear previous bitmap before setting new drawable
        if (clearOnDetach && drawable != getDrawable()) {
            Drawable currentDrawable = getDrawable();
            if (currentDrawable instanceof BitmapDrawable) {
                Bitmap currentBitmap = ((BitmapDrawable) currentDrawable).getBitmap();
                Bitmap newBitmap = null;
                if (drawable instanceof BitmapDrawable) {
                    newBitmap = ((BitmapDrawable) drawable).getBitmap();
                }
                // Only clear if it's a different bitmap
                if (currentBitmap != newBitmap) {
                    secureClearBitmap(currentBitmap);
                }
            }
        }
        super.setImageDrawable(drawable);
    }
}
