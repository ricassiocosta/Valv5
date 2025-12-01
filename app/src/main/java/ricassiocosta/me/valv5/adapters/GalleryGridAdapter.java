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

package ricassiocosta.me.valv5.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.icu.text.SimpleDateFormat;
import android.net.Uri;
import android.os.Bundle;
import ricassiocosta.me.valv5.security.SecureLog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.io.File;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import android.util.LruCache;

import ricassiocosta.me.valv5.DirectoryFragment;
import ricassiocosta.me.valv5.R;
import ricassiocosta.me.valv5.adapters.viewholders.GalleryGridViewHolder;
import ricassiocosta.me.valv5.data.FileType;
import ricassiocosta.me.valv5.data.GalleryFile;
import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.data.UniqueLinkedList;
import ricassiocosta.me.valv5.databinding.AdapterGalleryGridItemBinding;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.fastscroll.views.FastScrollRecyclerView;
import ricassiocosta.me.valv5.interfaces.IOnFileClicked;
import ricassiocosta.me.valv5.interfaces.IOnFileDeleted;
import ricassiocosta.me.valv5.interfaces.IOnSelectionModeChanged;
import ricassiocosta.me.valv5.utils.GlideStuff;
import ricassiocosta.me.valv5.utils.Settings;
import ricassiocosta.me.valv5.security.SecureMemoryManager;
import ricassiocosta.me.valv5.utils.StringStuff;
import ricassiocosta.me.valv5.viewmodel.GalleryViewModel;

public class GalleryGridAdapter extends RecyclerView.Adapter<GalleryGridViewHolder> implements IOnSelectionModeChanged, FastScrollRecyclerView.SectionedAdapter {
    private static final String TAG = "GalleryFolderAdapter";

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.ENGLISH);
    
    // Thread pool for loading thumbnails - limited to prevent OOM
    private static final int THUMBNAIL_LOADER_THREADS = 4;
    private ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(THUMBNAIL_LOADER_THREADS);
    private volatile boolean isShutdown = false;
    
    // Track pending thumbnail loads to cancel when ViewHolder is recycled
    private final Map<Uri, Future<?>> pendingTasks = new ConcurrentHashMap<>();
    
    // LRU cache for metadata results - avoids re-reading encrypted files
    private static final int METADATA_CACHE_SIZE = 100;
    private final LruCache<Uri, Encryption.V5MetadataResult> metadataCache = new LruCache<>(METADATA_CACHE_SIZE);

    private final boolean isRootDir;
    private boolean showFileNames, selectMode;
    private int lastSelectedPos;
    private String nestedPath;

    private final WeakReference<FragmentActivity> weakReference;
    private final List<GalleryFile> galleryFiles;
    private final UniqueLinkedList<GalleryFile> selectedFiles;
    private final Password password;
    private final GalleryViewModel galleryViewModel;
    private IOnFileDeleted onFileDeleted;
    private IOnFileClicked onFileCLicked;
    private IOnSelectionModeChanged onSelectionModeChanged;

    @NonNull
    @Override
    public String getSectionName(int position) {
        return simpleDateFormat.format(new Date(galleryFiles.get(position).getLastModified()));
    }

    record Payload(int type) {
        static final int TYPE_SELECT_ALL = 0;
        static final int TYPE_TOGGLE_FILENAME = 1;
        static final int TYPE_NEW_FILENAME = 2;
        static final int TYPE_LOADED_NOTE = 3;
    }

    public GalleryGridAdapter(FragmentActivity context, @NonNull List<GalleryFile> galleryFiles, boolean showFileNames, boolean isRootDir, GalleryViewModel galleryViewModel) {
        this.weakReference = new WeakReference<>(context);
        this.galleryFiles = galleryFiles;
        this.showFileNames = showFileNames;
        this.galleryViewModel = galleryViewModel;
        this.selectedFiles = new UniqueLinkedList<>();
        this.isRootDir = isRootDir;
        password = Password.getInstance();
        
        // Note: metadataCache is NOT registered with SecureMemoryManager
        // It only contains non-sensitive metadata (URIs, file types), not decrypted content
    }

    public void setNestedPath(String nestedPath) {
        this.nestedPath = nestedPath;
    }

    public void setOnFileCLicked(IOnFileClicked onFileCLicked) {
        this.onFileCLicked = onFileCLicked;
    }

    public void setOnFileDeleted(IOnFileDeleted onFileDeleted) {
        this.onFileDeleted = onFileDeleted;
    }

    public void setOnSelectionModeChanged(IOnSelectionModeChanged onSelectionModeChanged) {
        this.onSelectionModeChanged = onSelectionModeChanged;
    }

    @NonNull
    @Override
    public GalleryGridViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        AdapterGalleryGridItemBinding binding = AdapterGalleryGridItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new GalleryGridViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull GalleryGridViewHolder holder, int position) {
        FragmentActivity context = weakReference.get();
        if (context == null || context.isDestroyed()) {
            return;
        }
        GalleryFile galleryFile = galleryFiles.get(position);

        updateSelectedView(holder, galleryFile);
        holder.binding.txtName.setVisibility(showFileNames || galleryFile.isDirectory() ? View.VISIBLE : View.GONE);
        holder.binding.imageView.setImageDrawable(null);
        
        boolean isWebpFile = galleryFile.getOriginalName() != null && galleryFile.getOriginalName().toLowerCase().endsWith(".webp");
        if (!isRootDir && (galleryFile.isGif() || galleryFile.isVideo() || galleryFile.isDirectory() || galleryFile.isText() || (galleryFile.isImage() && isWebpFile))) {
            holder.binding.imgType.setVisibility(View.VISIBLE);
            int drawableId;
            if (galleryFile.isGif() && isWebpFile) {
                drawableId = R.drawable.ic_round_webp_24;
            } else if (galleryFile.isGif()) {
                drawableId = R.drawable.ic_round_gif_24;
            } else if (galleryFile.isVideo()) {
                drawableId = R.drawable.ic_outline_video_file_24;
            } else if (galleryFile.isText()) {
                drawableId = R.drawable.outline_text_snippet_24;
            } else if (galleryFile.isImage() && isWebpFile) {
                drawableId = R.drawable.ic_round_webp_24;
            } else {
                drawableId = R.drawable.ic_round_folder_open_24;
            }
            holder.binding.imgType.setImageDrawable(ResourcesCompat.getDrawable(context.getResources(), drawableId, context.getTheme()));
        } else {
            holder.binding.imgType.setVisibility(View.GONE);
        }
        holder.binding.hasDescription.setVisibility(!isRootDir && galleryFile.hasNote() ? View.VISIBLE : View.GONE);

        holder.binding.imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        holder.binding.textView.setVisibility(View.GONE);
        holder.binding.textView.setText(null);
        if (galleryFile.isAllFolder()) {
            holder.binding.imageView.setVisibility(View.VISIBLE);
            holder.binding.imageView.setImageDrawable(ResourcesCompat.getDrawable(context.getResources(), R.drawable.round_all_inclusive_24, context.getTheme()));
            holder.binding.imageView.setScaleType(ImageView.ScaleType.CENTER);
            holder.binding.txtName.setText(context.getString(R.string.gallery_all));
        } else if (galleryFile.isDirectory()) {
            holder.binding.imageView.setVisibility(View.VISIBLE);
            galleryFile.findFilesInDirectory(context, () -> {
                FragmentActivity currentContext = weakReference.get();
                if (currentContext != null && !currentContext.isDestroyed()) {
                    currentContext.runOnUiThread(() -> {
                        int bindingAdapterPosition = holder.getBindingAdapterPosition();
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            galleryViewModel.getOnAdapterItemChanged().onChanged(bindingAdapterPosition);
                        }
                    });
                }
            });
            GalleryFile firstFile = galleryFile.getFirstFile();
            if (firstFile == null) {
                Glide.with(context).clear(holder.binding.imageView);
            } else if (firstFile.getThumbUri() != null) {
                Glide.with(context)
                        .load(firstFile.getThumbUri())
                        .apply(GlideStuff.getGridThumbnailOptions())
                        .into(holder.binding.imageView);
            } else if (firstFile.mayBeV5CompositeFile()) {
                loadCompositeThumb(context, firstFile, holder);
            } else {
                Glide.with(context).clear(holder.binding.imageView);
            }
            holder.binding.txtName.setText(context.getString(R.string.gallery_adapter_folder_name, galleryFile.getNameWithPath(), galleryFile.getFileCount()));
        } else if (galleryFile.isText()) {
            holder.binding.imageView.setVisibility(View.GONE);
            holder.binding.textView.setText(galleryFile.getText() == null ? context.getString(R.string.loading) : galleryFile.getText());
            holder.binding.textView.setVisibility(View.VISIBLE);
            setItemFilename(holder, context, galleryFile);
            if (galleryFile.getText() == null) {
                readText(context, galleryFile, holder);
            }
        } else {
            holder.binding.imageView.setVisibility(View.VISIBLE);
            
            if (galleryFile.getThumbUri() == null && galleryFile.mayBeV5CompositeFile()) {
                loadCompositeThumb(context, galleryFile, holder);
            } else if (galleryFile.getThumbUri() != null) {
                Glide.with(context)
                        .load(galleryFile.getThumbUri())
                        .apply(GlideStuff.getGridThumbnailOptions())
                        .listener(new RequestListener<>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                                if (e != null) {
                                    for (Throwable t : e.getRootCauses()) {
                                        if (t instanceof InvalidPasswordException) {
                                            removeItem(holder.getBindingAdapterPosition());
                                            break;
                                        }
                                    }
                                }
                                return true;
                            }

                            @Override
                            public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                                return false;
                            }
                        })
                        .into(holder.binding.imageView);
            } else {
                Glide.with(context)
                        .load(R.drawable.outline_broken_image_24)
                        .centerInside()
                        .into(holder.binding.imageView);
            }
            setItemFilename(holder, context, galleryFile);
        }
        if (!galleryFile.isDirectory() && !galleryFile.isAllFolder()) {
            loadOriginalFilename(galleryFile, context, holder);
        }
        setClickListener(holder, context, galleryFile);
    }

    private void loadOriginalFilename(@NonNull GalleryFile galleryFile, FragmentActivity context, @NonNull GalleryGridViewHolder holder) {
        if (galleryFile.isDirectory() || galleryFile.getOriginalName() != null) {
            return;
        }
        new Thread(() -> {
            Encryption.Streams streams = null;
            try {
                InputStream inputStream = context.getContentResolver().openInputStream(galleryFile.getUri());
                streams = Encryption.getCipherInputStream(inputStream, password.getPassword(), false, galleryFile.getVersion());
                String originalFilename = streams.getOriginalFileName();
                galleryFile.setOriginalName(originalFilename);

                if (originalFilename != null && originalFilename.toLowerCase().endsWith(".webp")) {
                    InputStream decryptedStream = streams.getInputStream();
                    if (decryptedStream != null) {
                        InputStream bufferedDecryptedStream = new BufferedInputStream(decryptedStream);
                        boolean isAnimated = Encryption.isAnimatedWebp(bufferedDecryptedStream);
                        galleryFile.setFileTypeFromContent(isAnimated);
                    }
                }

                FragmentActivity currentContext = weakReference.get();
                if (currentContext != null && !currentContext.isDestroyed()) {
                    currentContext.runOnUiThread(() -> {
                        int pos = galleryFiles.indexOf(galleryFile);
                        if (pos >= 0) {
                            notifyItemChanged(pos, new Payload(Payload.TYPE_NEW_FILENAME));
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                galleryFile.setOriginalName("");
            } finally {
                if (streams != null) {
                    streams.close();
                }
            }
        }).start();
    }

    private void readText(FragmentActivity context, GalleryFile galleryFile, GalleryGridViewHolder holder) {
        new Thread(() -> {
            String text = Encryption.readEncryptedTextFromUri(galleryFile.getUri(), context, galleryFile.getVersion(), password.getPassword());
            galleryFile.setText(text);
            FragmentActivity currentContext = weakReference.get();
            if (currentContext != null && !currentContext.isDestroyed()) {
                currentContext.runOnUiThread(() -> {
                    int pos = galleryFiles.indexOf(galleryFile);
                    if (pos >= 0) {
                        galleryViewModel.getOnAdapterItemChanged().onChanged(pos);
                    }
                });
            }
        }).start();
    }

    private void setItemFilename(@NonNull GalleryGridViewHolder holder, Context context, @NonNull GalleryFile galleryFile) {
        if (galleryFile.getSize() > 0) {
            holder.binding.txtName.setText(context.getString(R.string.gallery_adapter_file_name, galleryFile.getName(), StringStuff.bytesToReadableString(galleryFile.getSize())));
        } else {
            holder.binding.txtName.setText(galleryFile.getName());
        }
    }

    private void setClickListener(@NonNull GalleryGridViewHolder holder, FragmentActivity context, GalleryFile galleryFile) {
        holder.binding.layout.setOnClickListener(v -> {
            final int pos = holder.getBindingAdapterPosition();
            if (galleryFile.isAllFolder()) {
                if (!selectMode) {
                    Navigation.findNavController(holder.binding.layout).navigate(R.id.action_directory_to_directory_all);
                }
            } else if (selectMode) {
                if (isRootDir || !galleryFile.isDirectory()) {
                    if (!selectedFiles.contains(galleryFile)) {
                        selectedFiles.add(galleryFile);
                        lastSelectedPos = pos;
                    } else {
                        selectedFiles.remove(galleryFile);
                        if (selectedFiles.isEmpty()) {
                            setSelectMode(false);
                        }
                        lastSelectedPos = -1;
                    }
                    updateSelectedView(holder, galleryFile);
                }
            } else {
                if (galleryFile.isDirectory()) {
                    Bundle bundle = new Bundle();
                    if (isRootDir) {
                        bundle.putString(DirectoryFragment.ARGUMENT_DIRECTORY, DocumentFile.fromTreeUri(context, galleryFile.getUri()).getUri().toString());
                        bundle.putString(DirectoryFragment.ARGUMENT_NESTED_PATH, "/" + new File(galleryFile.getUri().getPath()).getName());
                    } else if (nestedPath != null) {
                        bundle.putString(DirectoryFragment.ARGUMENT_DIRECTORY, galleryFile.getUri().toString());
                        bundle.putString(DirectoryFragment.ARGUMENT_NESTED_PATH, nestedPath + "/" + new File(galleryFile.getUri().getPath()).getName());
                    } else {
                        bundle.putString(DirectoryFragment.ARGUMENT_DIRECTORY, galleryFile.getUri().toString());
                    }
                    galleryViewModel.setClickedDirectoryUri(galleryFile.getUri());
                    Navigation.findNavController(holder.binding.layout).navigate(R.id.action_directory_self, bundle);
                } else {
                    if (onFileCLicked != null) {
                        onFileCLicked.onClick(pos);
                    }
                }
            }
        });
        holder.binding.layout.setOnLongClickListener(v -> {
            if (!galleryFile.isAllFolder() && (isRootDir || !galleryFile.isDirectory())) {
                int pos = holder.getBindingAdapterPosition();
                if (!selectMode) {
                    setSelectMode(true);
                    holder.binding.layout.performClick();
                } else {
                    if (lastSelectedPos >= 0 && !selectedFiles.contains(galleryFile)) {
                        int minPos = Math.min(pos, lastSelectedPos);
                        int maxPos = Math.max(pos, lastSelectedPos);
                        if (minPos >= 0 && maxPos < galleryFiles.size()) {
                            for (int i = minPos; i >= 0 && i <= maxPos && i < galleryFiles.size(); i++) {
                                GalleryFile gf = galleryFiles.get(i);
                                if (gf != null && !selectedFiles.contains(gf)) {
                                    selectedFiles.add(gf);
                                }
                            }
                            notifyItemRangeChanged(minPos, 1 + (maxPos - minPos), new Payload(Payload.TYPE_SELECT_ALL));
                        }
                    } else {
                        holder.binding.layout.performClick();
                    }
                }
                lastSelectedPos = pos;
            }
            return true;
        });
    }

    @Override
    public void onBindViewHolder(@NonNull GalleryGridViewHolder holder, int position, @NonNull List<Object> payloads) {
        boolean found = false;
        for (Object o : payloads) {
            if (o instanceof Payload payload) {
                if (payload.type == Payload.TYPE_SELECT_ALL) {
                    updateSelectedView(holder, galleryFiles.get(position));
                    found = true;
                    break;
                } else if (payload.type == Payload.TYPE_TOGGLE_FILENAME) {
                    GalleryFile galleryFile = galleryFiles.get(position);
                    holder.binding.txtName.setVisibility(showFileNames || galleryFile.isDirectory() ? View.VISIBLE : View.GONE);
                    found = true;
                } else if (payload.type == Payload.TYPE_NEW_FILENAME) {
                    FragmentActivity context = weakReference.get();
                    if (context == null || context.isDestroyed()) {
                        return;
                    }
                    GalleryFile galleryFile = galleryFiles.get(position);
                    setItemFilename(holder, context, galleryFile);
                    boolean isWebpFile = galleryFile.getOriginalName() != null && galleryFile.getOriginalName().toLowerCase().endsWith(".webp");
                    if (!isRootDir && (galleryFile.isGif() || galleryFile.isVideo() || galleryFile.isDirectory() || galleryFile.isText() || (galleryFile.isImage() && isWebpFile))) {
                        holder.binding.imgType.setVisibility(View.VISIBLE);
                        int drawableId;
                        if (galleryFile.isGif() && isWebpFile) {
                            drawableId = R.drawable.ic_round_webp_24;
                        } else if (galleryFile.isGif()) {
                            drawableId = R.drawable.ic_round_gif_24;
                        } else if (galleryFile.isVideo()) {
                            drawableId = R.drawable.ic_outline_video_file_24;
                        } else if (galleryFile.isText()) {
                            drawableId = R.drawable.outline_text_snippet_24;
                        } else if (galleryFile.isImage() && isWebpFile) {
                            drawableId = R.drawable.ic_round_webp_24;
                        } else {
                            drawableId = R.drawable.ic_round_folder_open_24;
                        }
                        holder.binding.imgType.setImageDrawable(ResourcesCompat.getDrawable(context.getResources(), drawableId, context.getTheme()));
                    } else {
                        holder.binding.imgType.setVisibility(View.GONE);
                    }
                    found = true;
                }
            }
        }
        if (!found) {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    private void setSelectMode(boolean selectionMode) {
        if (selectionMode && !selectMode) {
            selectMode = true;
            notifyItemRangeChanged(0, galleryFiles.size(), new Payload(Payload.TYPE_SELECT_ALL));
        } else if (!selectionMode && selectMode) {
            selectMode = false;
            lastSelectedPos = -1;
            selectedFiles.clear();
            notifyItemRangeChanged(0, galleryFiles.size(), new Payload(Payload.TYPE_SELECT_ALL));
        }
        if (onSelectionModeChanged != null) {
            onSelectionModeChanged.onSelectionModeChanged(selectMode);
        }
    }

    private void updateSelectedView(GalleryGridViewHolder holder, GalleryFile galleryFile) {
        if (!galleryFile.isAllFolder() && selectMode && (isRootDir || !galleryFile.isDirectory())) {
            holder.binding.checked.setVisibility(View.VISIBLE);
            holder.binding.checked.setChecked(selectedFiles.contains(galleryFile));
        } else {
            holder.binding.checked.setVisibility(View.GONE);
            holder.binding.checked.setChecked(false);
        }
    }

    private void removeItem(int pos) {
        synchronized (LOCK) {
            if (pos >= 0 && pos < galleryFiles.size()) {
                galleryFiles.remove(pos);
                notifyItemRemoved(pos);
                if (onFileDeleted != null) {
                    onFileDeleted.onFileDeleted(pos);
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return galleryFiles.size();
    }

    @Override
    public void onSelectionModeChanged(boolean inSelectionMode) {
        setSelectMode(inSelectionMode);
    }

    public void selectAll() {
        synchronized (LOCK) {
            selectedFiles.clear();
            if (isRootDir) {
                List<GalleryFile> filtered = new ArrayList<>(galleryFiles.size() - 1);
                for (GalleryFile f : galleryFiles) {
                    if (!f.isAllFolder()) {
                        filtered.add(f);
                    }
                }
                selectedFiles.addAll(filtered);
            } else {
                for (GalleryFile g : galleryFiles) {
                    if (!g.isDirectory()) {
                        selectedFiles.add(g);
                    }
                }
            }
            notifyItemRangeChanged(0, galleryFiles.size(), new Payload(Payload.TYPE_SELECT_ALL));
        }
    }

    public boolean toggleFilenames() {
        showFileNames = !showFileNames;
        notifyItemRangeChanged(0, galleryFiles.size(), new Payload(Payload.TYPE_TOGGLE_FILENAME));
        return showFileNames;
    }

    /**
     * Load thumbnail and metadata from V5 composite file.
     * For V5 files, the thumbnail is stored within the main encrypted file.
     * Also updates the file type from the encrypted metadata.
     * Uses ExecutorService for better thread management and LruCache for caching.
     */
    private void loadCompositeThumb(FragmentActivity context, GalleryFile galleryFile, @NonNull GalleryGridViewHolder holder) {
        // Don't process if executor is shutdown
        if (isShutdown) {
            return;
        }
        
        Uri fileUri = galleryFile.getUri();
        
        // Set the current file URI on the holder for tracking
        holder.setCurrentFileUri(fileUri);
        
        // Check metadata cache first - avoids re-reading encrypted file
        Encryption.V5MetadataResult cachedMetadata = metadataCache.get(fileUri);
        if (cachedMetadata != null) {
            // Verify context is still valid before applying
            if (!context.isDestroyed()) {
                applyMetadata(context, galleryFile, holder, cachedMetadata, fileUri);
            }
            return;
        }
        
        // Cancel any pending task for this URI
        Future<?> existingTask = pendingTasks.get(fileUri);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(true);
        }
        
        // Submit new task to executor - wrap in try-catch for RejectedExecutionException
        try {
            Future<?> task = thumbnailExecutor.submit(() -> {
                // Check if this holder is still showing this file
                if (!fileUri.equals(holder.getCurrentFileUri())) {
                    return;
                }
                
                try {
                    char[] pwd = Password.getInstance().getPassword();
                    
                    Encryption.V5MetadataResult metadata = Encryption.readCompositeMetadata(galleryFile.getUri(), context, pwd);
                    if (metadata != null) {
                        // Cache the metadata result
                        metadataCache.put(fileUri, metadata);
                        
                        // Apply metadata on UI thread
                        FragmentActivity currentContext = weakReference.get();
                        if (currentContext != null && !currentContext.isDestroyed() && fileUri.equals(holder.getCurrentFileUri())) {
                            currentContext.runOnUiThread(() -> {
                                if (fileUri.equals(holder.getCurrentFileUri())) {
                                    applyMetadata(currentContext, galleryFile, holder, metadata, fileUri);
                                }
                            });
                        }
                    } else {
                        showBrokenImage(holder, fileUri, galleryFile);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    showBrokenImage(holder, fileUri, galleryFile);
                } finally {
                    pendingTasks.remove(fileUri);
                }
            });
            
            pendingTasks.put(fileUri, task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Executor was shutdown, ignore
            SecureLog.w(TAG, "Executor shutdown, ignoring thumbnail load request");
        }
    }
    
    /**
     * Apply metadata to the holder - used both for cached and fresh results.
     */
    private void applyMetadata(FragmentActivity context, GalleryFile galleryFile, 
                               GalleryGridViewHolder holder, Encryption.V5MetadataResult metadata, Uri fileUri) {
        // Update file type from metadata (critical for video/gif detection)
        boolean typeChanged = false;
        if (metadata.fileType >= 0) {
            FileType realType = FileType.fromTypeAndVersion(metadata.fileType, 5);
            FileType oldType = galleryFile.getFileType();
            
            String originalName = galleryFile.getOriginalName();
            boolean isWebp = originalName != null && originalName.toLowerCase().endsWith(".webp");
            
            if (!isWebp || oldType == FileType.DIRECTORY) {
                galleryFile.setOverriddenFileType(realType);
                typeChanged = oldType != realType;
            }
        }
        
        if (metadata.originalName != null && !metadata.originalName.isEmpty()) {
            galleryFile.setOriginalName(metadata.originalName);
        }
        
        if (metadata.thumbUri != null) {
            galleryFile.setThumbUri(metadata.thumbUri);
            Glide.with(context)
                    .load(metadata.thumbUri)
                    .apply(GlideStuff.getGridThumbnailOptions())
                    .into(holder.binding.imageView);
        } else {
            Glide.with(context)
                    .load(R.drawable.outline_broken_image_24)
                    .centerInside()
                    .into(holder.binding.imageView);
        }
        
        // Post the notifyItemChanged to avoid calling it during layout/scroll
        if (typeChanged) {
            final int pos = galleryFiles.indexOf(galleryFile);
            if (pos >= 0) {
                holder.itemView.post(() -> {
                    if (!isShutdown) {
                        notifyItemChanged(pos, new Payload(Payload.TYPE_NEW_FILENAME));
                    }
                });
            }
        }
    }
    
    /**
     * Show broken image placeholder.
     */
    private void showBrokenImage(GalleryGridViewHolder holder, Uri fileUri, GalleryFile galleryFile) {
        FragmentActivity currentContext = weakReference.get();
        if (currentContext != null && !currentContext.isDestroyed() && fileUri.equals(holder.getCurrentFileUri())) {
            currentContext.runOnUiThread(() -> {
                if (fileUri.equals(holder.getCurrentFileUri())) {
                    Glide.with(currentContext)
                            .load(R.drawable.outline_broken_image_24)
                            .centerInside()
                            .into(holder.binding.imageView);
                    // Post the notifyItemChanged to avoid calling it during layout/scroll
                    final int pos = galleryFiles.indexOf(galleryFile);
                    if (pos >= 0) {
                        holder.itemView.post(() -> {
                            if (!isShutdown) {
                                notifyItemChanged(pos, new Payload(Payload.TYPE_NEW_FILENAME));
                            }
                        });
                    }
                }
            });
        }
    }
    
    @Override
    public void onViewRecycled(@NonNull GalleryGridViewHolder holder) {
        super.onViewRecycled(holder);
        
        // Cancel pending thumbnail load for this ViewHolder
        Uri fileUri = holder.getCurrentFileUri();
        if (fileUri != null) {
            Future<?> pendingTask = pendingTasks.remove(fileUri);
            if (pendingTask != null && !pendingTask.isDone()) {
                pendingTask.cancel(true);
            }
            holder.setCurrentFileUri(null);
        }
        
        // Clear the Glide request to free memory
        FragmentActivity context = weakReference.get();
        if (context != null && !context.isDestroyed()) {
            Glide.with(context).clear(holder.binding.imageView);
        }
    }
    
    /**
     * Call this method when the adapter is being destroyed to shutdown the executor.
     */
    public void shutdown() {
        isShutdown = true;
        thumbnailExecutor.shutdownNow();
        pendingTasks.clear();
        metadataCache.evictAll();
    }

    @NonNull
    public List<GalleryFile> getSelectedFiles() {
        return selectedFiles;
    }
}
