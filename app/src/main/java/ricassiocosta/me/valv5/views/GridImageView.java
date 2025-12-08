/*
 * Valv-Android
 * Copyright (C) 2024 Arctosoft AB
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

package ricassiocosta.me.valv5.views;

import android.content.Context;
import android.util.AttributeSet;

/**
 * A grid-optimized ImageView that maintains a 1:1.2 aspect ratio.
 * Extends SecureImageView for secure bitmap handling.
 */
public class GridImageView extends SecureImageView {

    public GridImageView(Context context) {
        super(context);
        // Disable clear on detach for RecyclerView recycling
        setClearOnDetach(false);
    }

    public GridImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClearOnDetach(false);
    }

    public GridImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClearOnDetach(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = getMeasuredWidth();
        setMeasuredDimension(width, (int) (width * 1.2));
    }
}
