package ricassiocosta.me.valv5;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import ricassiocosta.me.valv5.security.SecureLog;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ricassiocosta.me.valv5.adapters.GalleryGridAdapter;
import ricassiocosta.me.valv5.data.FileType;
import ricassiocosta.me.valv5.data.GalleryFile;
import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.data.UniqueLinkedList;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.index.IndexManager;
import ricassiocosta.me.valv5.security.SecureMemoryManager;
import ricassiocosta.me.valv5.utils.FileStuff;
import ricassiocosta.me.valv5.utils.Settings;

public class DirectoryAllFragment extends DirectoryBaseFragment {
    private static final String TAG = "DirectoryAllFragment";

    // Thread pool with fixed size based on CPU cores (max 4 to avoid overhead)
    private static final int THREAD_POOL_SIZE = Math.min(Runtime.getRuntime().availableProcessors(), 4);
    
    // Batch size for incremental UI updates
    private static final int BATCH_SIZE = 20;
    
    // In-memory cache for password verification results per folder URI
    private final Map<String, Boolean> passwordVerifiedCache = new ConcurrentHashMap<>();
    
    private final AtomicInteger foundFiles = new AtomicInteger(0);
    private final AtomicInteger foundFolders = new AtomicInteger(0);
    
    // Flag to track if loading is complete
    private final AtomicBoolean loadingComplete = new AtomicBoolean(false);
    
    // Thread-safe list of all files found (for when user changes filter)
    private final List<GalleryFile> allFilesFound = Collections.synchronizedList(new UniqueLinkedList<>());
    
    // Pending files batch to add to UI
    private final List<GalleryFile> pendingBatch = Collections.synchronizedList(new ArrayList<>());
    
    // Track the active filter during lazy loading
    private int activeFilter = FILTER_ALL;
    
    private ExecutorService executorService;
    private Thread mainScanThread;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.layoutFabsAdd.setVisibility(View.GONE);
        binding.noMedia.setVisibility(View.GONE);
        
        // Register password cache with SecureMemoryManager so it's cleared on lock
        SecureMemoryManager.getInstance().registerMap(passwordVerifiedCache);
    }

    public void init() {
        Context context = requireContext();
        settings = Settings.getInstance(context);

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (galleryViewModel.isViewpagerVisible()) {
                    showViewpager(false, galleryViewModel.getCurrentPosition(), false);
                } else if (galleryViewModel.isInSelectionMode()) {
                    galleryGridAdapter.onSelectionModeChanged(false);
                } else if (!navController.popBackStack()) {
                    FragmentActivity activity = requireActivity();
                    Password.lock(activity, false);
                    activity.finish();
                    if (!settings.exitOnLock()) {
                        startActivity(new Intent(context, MainActivity.class));
                    }
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), callback);

        galleryViewModel.setAllFolder(true);
        galleryViewModel.setRootDir(false);
        if (!initActionBar(true)) { // getSupportActionBar() is null directly after orientation change
            binding.recyclerView.post(() -> initActionBar(true));
        }

        galleryViewModel.setOnAdapterItemChanged(new ricassiocosta.me.valv5.interfaces.IOnAdapterItemChanged() {
            @Override
            public void onChanged(int pos) {
                // Default: use payloads to prevent full rebind and avoid flickering
                galleryPagerAdapter.notifyItemChanged(pos, Boolean.FALSE);
                galleryGridAdapter.notifyItemChanged(pos, new GalleryGridAdapter.Payload(GalleryGridAdapter.Payload.TYPE_NEW_FILENAME));
            }
            
            @Override
            public void onChanged(int pos, int payloadType) {
                galleryPagerAdapter.notifyItemChanged(pos, Boolean.FALSE);
                if (payloadType == GalleryGridAdapter.Payload.TYPE_DIRECTORY_LOADED || 
                    payloadType == GalleryGridAdapter.Payload.TYPE_TEXT_LOADED) {
                    // These need full rebind to update thumbnail/text content
                    galleryGridAdapter.notifyItemChanged(pos);
                } else {
                    galleryGridAdapter.notifyItemChanged(pos, new GalleryGridAdapter.Payload(payloadType));
                }
            }
        });

        setupViewpager();
        setupGrid();
        setClickListeners();

        if (!galleryViewModel.isInitialised()) {
            // Load index if available (for faster filtering)
            loadIndexIfAvailable();
            
            // Start with RANDOM order - enables lazy loading
            orderBy = ORDER_BY_RANDOM;
            findAllFilesLazy();
        }

        initViewModels();
    }
    
    /**
     * Load the index from disk if available. This enables fast filtering without decryption.
     */
    private void loadIndexIfAvailable() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        
        char[] password = Password.getInstance().getPassword();
        if (password == null) return;
        
        List<Uri> rootDirs = settings.getGalleryDirectoriesAsUri(true);
        if (rootDirs.isEmpty()) return;
        
        Uri rootUri = rootDirs.get(0);
        
        // Load index in background to not block UI
        new Thread(() -> {
            IndexManager indexManager = IndexManager.getInstance();
            boolean loaded = indexManager.loadIndex(activity, rootUri, password);
            if (loaded) {
                SecureLog.d(TAG, "Index loaded successfully with " + indexManager.getEntryCount() + " entries");
            }
        }).start();
    }

    private void setClickListeners() {
        binding.fabRemoveFolders.setOnClickListener(v -> {
            deleteViewModel.getFilesToDelete().clear();
            deleteViewModel.getFilesToDelete().addAll(galleryGridAdapter.getSelectedFiles());

            BottomSheetDeleteFragment bottomSheetDeleteFragment = new BottomSheetDeleteFragment();
            FragmentManager childFragmentManager = getChildFragmentManager();
            bottomSheetDeleteFragment.show(childFragmentManager, null);
        });
    }

    void onSelectionModeChanged(boolean inSelectionMode) {
        galleryViewModel.setInSelectionMode(inSelectionMode);
        if (inSelectionMode) {
            binding.layoutFabsRemoveFolders.setVisibility(View.VISIBLE);
        } else {
            binding.layoutFabsRemoveFolders.setVisibility(View.GONE);
        }
        requireActivity().invalidateOptionsMenu();
    }

    void addRootFolders() {

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Cancel any pending filter wait
        filterWaitCancelled.set(true);
        if (filterWaitThread != null) {
            filterWaitThread.interrupt();
            filterWaitThread = null;
        }
        // Shutdown executor and clear cache when view is destroyed
        shutdownExecutor();
        passwordVerifiedCache.clear();
        allFilesFound.clear();
        pendingBatch.clear();
    }
    
    private void shutdownExecutor() {
        if (mainScanThread != null) {
            mainScanThread.interrupt();
            mainScanThread = null;
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
            executorService = null;
        }
    }
    
    /**
     * Check if background loading is still in progress
     */
    @Override
    protected boolean isLoadingInProgress() {
        return !loadingComplete.get();
    }
    
    // Flag to track if user cancelled waiting for filter
    private final AtomicBoolean filterWaitCancelled = new AtomicBoolean(false);
    
    // Thread that's waiting for loading to complete (for filter)
    private Thread filterWaitThread;
    
    // Flag to track if filter overlay is showing (to update progress on it)
    private final AtomicBoolean filterOverlayShowing = new AtomicBoolean(false);
    
    /**
     * Show the full-screen loading overlay with cancel button.
     * Used when user selects a filter that requires all files to be loaded.
     */
    private void showFilterLoadingOverlay(int targetOrder) {
        FragmentActivity activity = getActivity();
        if (activity == null || !isSafe()) {
            return;
        }
        
        filterWaitCancelled.set(false);
        filterOverlayShowing.set(true);
        
        activity.runOnUiThread(() -> {
            // Hide bottom indicator - only one should be visible at a time
            binding.loadingIndicatorBottom.setVisibility(View.GONE);
            
            // Show full overlay
            binding.cLLoading.txtLoading.setText(R.string.gallery_loading_finalizing);
            binding.cLLoading.txtProgress.setText(getString(R.string.gallery_loading_all_progress, foundFiles.get(), foundFolders.get()));
            binding.cLLoading.txtProgress.setVisibility(View.VISIBLE);
            binding.cLLoading.btnCancelLoading.setVisibility(View.VISIBLE);
            binding.cLLoading.cLLoading.setVisibility(View.VISIBLE);
            
            // Setup cancel button
            binding.cLLoading.btnCancelLoading.setOnClickListener(v -> {
                // Cancel waiting and revert to random
                filterWaitCancelled.set(true);
                filterOverlayShowing.set(false);
                if (filterWaitThread != null) {
                    filterWaitThread.interrupt();
                }
                
                // Hide overlay
                binding.cLLoading.cLLoading.setVisibility(View.GONE);
                binding.cLLoading.btnCancelLoading.setVisibility(View.GONE);
                
                // Show bottom indicator again if still loading
                if (isLoadingInProgress()) {
                    binding.loadingIndicatorBottom.setVisibility(View.VISIBLE);
                }
                
                // Revert to random order
                orderBy = ORDER_BY_RANDOM;
            });
        });
        
        // Start thread to wait for loading completion
        filterWaitThread = new Thread(() -> {
            // Wait for the main scan thread to complete
            if (mainScanThread != null && mainScanThread.isAlive()) {
                try {
                    mainScanThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            
            // Check if cancelled
            if (filterWaitCancelled.get() || !isSafe()) {
                return;
            }
            
            filterOverlayShowing.set(false);
            
            FragmentActivity act = getActivity();
            if (act == null) {
                return;
            }
            
            // Hide overlay and apply the filter
            act.runOnUiThread(() -> {
                binding.cLLoading.cLLoading.setVisibility(View.GONE);
                binding.cLLoading.btnCancelLoading.setVisibility(View.GONE);
            });
            
            // Apply the sort
            DirectoryAllFragment.super.orderBy(targetOrder);
        });
        filterWaitThread.start();
    }
    
    /**
     * Override orderBy to handle the case where loading is still in progress.
     * If user selects a non-random filter while loading, show overlay with cancel option.
     */
    @Override
    @SuppressLint("NotifyDataSetChanged")
    void orderBy(int order) {
        if (order != ORDER_BY_RANDOM && isLoadingInProgress()) {
            // User wants a sorted view but loading isn't complete
            // Show full-screen overlay with cancel button
            showFilterLoadingOverlay(order);
            this.orderBy = order;
        } else {
            // Random order or loading complete - proceed normally
            super.orderBy(order);
            this.orderBy = order;
        }
    }

    /**
     * Override filterBy to track active filter during lazy loading.
     * This ensures newly loaded files respect the current filter.
     */
    @Override
    @SuppressLint("NotifyDataSetChanged")
    void filterBy(int filter) {
        activeFilter = filter;
        super.filterBy(filter);
    }

    /**
     * Lazy loading implementation - shows files incrementally as they are found.
     * Uses RANDOM order by default which doesn't require all files to be loaded.
     */
    private void findAllFilesLazy() {
        SecureLog.d(TAG, "findAllFilesLazy: start");
        foundFiles.set(0);
        foundFolders.set(0);
        loadingComplete.set(false);
        passwordVerifiedCache.clear();
        allFilesFound.clear();
        pendingBatch.clear();
        
        // Show subtle loading indicator (not blocking the UI)
        setLoadingBackground(true);
        
        // Shutdown any previous executor
        shutdownExecutor();
        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        
        mainScanThread = new Thread(() -> {
            long start = System.currentTimeMillis();
            List<Uri> directories = settings.getGalleryDirectoriesAsUri(true);
            FragmentActivity activity = getActivity();
            if (activity == null || !isSafe()) {
                loadingComplete.set(true);
                return;
            }
            
            List<Uri> uriFiles = new ArrayList<>(directories.size());
            for (Uri uri : directories) {
                DocumentFile documentFile = DocumentFile.fromTreeUri(activity, uri);
                if (documentFile.canRead()) {
                    uriFiles.add(documentFile.getUri());
                }
            }

            // Collect top-level items first
            List<GalleryFile> folders = new ArrayList<>();
            List<GalleryFile> topLevelFiles = new ArrayList<>();
            List<GalleryFile> filesToSearch = new ArrayList<>();
            
            for (Uri uri : uriFiles) {
                if (!isSafe()) {
                    loadingComplete.set(true);
                    return;
                }
                List<GalleryFile> filesInFolder = FileStuff.getFilesInFolder(activity, uri, true);
                for (GalleryFile foundFile : filesInFolder) {
                    if (foundFile.isDirectory()) {
                        boolean add = true;
                        for (GalleryFile addedFile : filesToSearch) {
                            if (foundFile.getNameWithPath().startsWith(addedFile.getNameWithPath() + "/")) {
                                add = false;
                                break;
                            }
                        }
                        if (add) {
                            filesToSearch.add(foundFile);
                        }
                    } else {
                        filesToSearch.add(foundFile);
                    }
                }
            }
            
            for (GalleryFile galleryFile : filesToSearch) {
                if (galleryFile.isDirectory()) {
                    folders.add(galleryFile);
                } else {
                    topLevelFiles.add(galleryFile);
                }
            }

            // Add top-level files immediately to show something fast
            if (!topLevelFiles.isEmpty()) {
                addFilesToUIIncremental(topLevelFiles);
            }

            // Process folders in parallel
            for (GalleryFile galleryFile : folders) {
                if (!isSafe()) {
                    break;
                }
                if (galleryFile.isDirectory()) {
                    executorService.submit(() -> {
                        if (!isSafe()) return;
                        findAllFilesInFolderLazy(galleryFile.getUri());
                    });
                }
            }
            
            // Wait for all folder scans to complete
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.MINUTES)) {
                    SecureLog.w(TAG, "findAllFilesLazy: timeout waiting for tasks");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                SecureLog.e(TAG, "findAllFilesLazy: interrupted", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            // Flush any remaining pending files
            flushPendingBatch();
            
            loadingComplete.set(true);
            SecureLog.d(TAG, "findAllFilesLazy: complete, found " + SecureLog.safeCount(allFilesFound.size(), "files") + ", took " + (System.currentTimeMillis() - start) + "ms");
            
            if (!isSafe()) {
                return;
            }
            
            activity.runOnUiThread(() -> {
                setLoadingBackground(false);
                galleryViewModel.setInitialised(true);
                if (galleryViewModel.getGalleryFiles().size() > MIN_FILES_FOR_FAST_SCROLL) {
                    binding.recyclerView.setFastScrollEnabled(true);
                }
            });
        });
        mainScanThread.start();
    }
    
    /**
     * Recursively find files in a folder and add them incrementally to UI
     */
    private void findAllFilesInFolderLazy(Uri uri) {
        FragmentActivity activity = getActivity();
        if (activity == null || !isSafe()) {
            return;
        }
        
        incrementFolders(1);
        List<GalleryFile> filesInFolder = FileStuff.getFilesInFolder(activity, uri, true);

        // Password check using cache
        if (!filesInFolder.isEmpty()) {
            GalleryFile fileToCheck = null;
            for (GalleryFile f : filesInFolder) {
                if (!f.isDirectory()) {
                    fileToCheck = f;
                    break;
                }
            }

            if (fileToCheck != null) {
                if (!isPasswordValidForFolder(activity, uri, fileToCheck)) {
                    filesInFolder.removeIf(f -> !f.isDirectory());
                }
            }
        }

        List<GalleryFile> filesFound = new ArrayList<>();
        List<GalleryFile> subFolders = new ArrayList<>();
        
        for (GalleryFile galleryFile : filesInFolder) {
            if (!isSafe()) {
                return;
            }
            if (galleryFile.isDirectory()) {
                subFolders.add(galleryFile);
            } else {
                filesFound.add(galleryFile);
            }
        }
        
        // Add files from this folder to UI
        if (!filesFound.isEmpty()) {
            addFilesToUIIncremental(filesFound);
        }
        
        // Update progress
        activity.runOnUiThread(this::updateLoadingProgress);
        
        // Recurse into subfolders
        for (GalleryFile subFolder : subFolders) {
            if (!isSafe()) {
                return;
            }
            findAllFilesInFolderLazy(subFolder.getUri());
        }
    }
    
    /**
     * Add files to UI incrementally in batches for better performance
     */
    @SuppressLint("NotifyDataSetChanged")
    private void addFilesToUIIncremental(List<GalleryFile> newFiles) {
        if (newFiles.isEmpty() || !isSafe()) {
            return;
        }
        
        // Add to master list (all files, unfiltered)
        allFilesFound.addAll(newFiles);
        incrementFiles(newFiles.size());
        
        // Filter files based on active filter before adding to UI
        List<GalleryFile> filesToAdd = new ArrayList<>();
        List<GalleryFile> filesToHide = new ArrayList<>();
        
        for (GalleryFile f : newFiles) {
            int fileType = getFileTypeFromIndexOrFile(f);
            if (activeFilter != FILTER_ALL && !f.isDirectory() && fileType != activeFilter) {
                // File doesn't match filter
                filesToHide.add(f);
            } else {
                // File matches filter or is directory
                filesToAdd.add(f);
            }
        }
        
        // Add files to pending batch (only those that match filter)
        synchronized (pendingBatch) {
            pendingBatch.addAll(filesToAdd);
            
            // Flush batch if large enough
            if (pendingBatch.size() >= BATCH_SIZE) {
                flushPendingBatch();
            }
        }
        
        // Store hidden files in galleryViewModel
        if (!filesToHide.isEmpty()) {
            synchronized (LOCK) {
                galleryViewModel.getHiddenFiles().addAll(filesToHide);
            }
        }
    }
    
    /**
     * Get file type from index cache if available, otherwise fall back to GalleryFile.getFileType().
     * This avoids expensive decryption for filtering when index is loaded.
     */
    private int getFileTypeFromIndexOrFile(GalleryFile f) {
        if (f.isDirectory()) {
            return FileType.TYPE_DIRECTORY;
        }
        
        // Try to get type from index cache first (fast path)
        IndexManager indexManager = IndexManager.getInstance();
        if (indexManager.isLoaded()) {
            int indexedType = indexManager.getType(f.getEncryptedName());
            if (indexedType != -1) {
                // Also update the GalleryFile's overridden type for consistency
                FileType fileType = FileType.fromTypeAndVersion(indexedType, f.getVersion());
                if (fileType != null) {
                    f.setOverriddenFileType(fileType);
                }
                return indexedType;
            }
        }
        
        // Fall back to GalleryFile type (may be placeholder for V5 files)
        FileType fileType = f.getFileType();
        if (fileType != null) {
            return fileType.type;
        }
        
        // Default to image type if unable to determine
        return FileType.TYPE_IMAGE;
    }
    
    /**
     * Flush pending batch to UI
     */
    @SuppressLint("NotifyDataSetChanged")
    private void flushPendingBatch() {
        FragmentActivity activity = getActivity();
        if (activity == null || !isSafe()) {
            return;
        }
        
        final List<GalleryFile> batch;
        synchronized (pendingBatch) {
            if (pendingBatch.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingBatch);
            pendingBatch.clear();
        }
        
        activity.runOnUiThread(() -> {
            if (!isSafe()) {
                return;
            }
            
            synchronized (LOCK) {
                int startPos = galleryViewModel.getGalleryFiles().size();
                
                // Shuffle the batch for random order
                Collections.shuffle(batch);
                
                galleryViewModel.addGalleryFiles(batch);
                galleryGridAdapter.notifyItemRangeInserted(startPos, batch.size());
                galleryPagerAdapter.notifyItemRangeInserted(startPos, batch.size());
            }
        });
    }
    
    /**
     * Show/hide subtle background loading indicator at bottom of screen.
     * Only shows if the full-screen overlay is not visible.
     */
    private void setLoadingBackground(boolean loading) {
        FragmentActivity activity = getActivity();
        if (activity == null || !isSafe()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (loading) {
                // Only show bottom indicator if overlay is not showing
                if (!filterOverlayShowing.get()) {
                    binding.loadingIndicatorText.setText(R.string.gallery_loading_background);
                    binding.loadingIndicatorBottom.setVisibility(View.VISIBLE);
                }
            } else {
                binding.loadingIndicatorBottom.setVisibility(View.GONE);
            }
        });
    }
    
    /**
     * Update loading progress text - updates both bottom indicator AND overlay if visible
     */
    private void updateLoadingProgress() {
        if (!isSafe()) {
            return;
        }
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }
        final String progressText = getString(R.string.gallery_loading_all_progress, foundFiles.get(), foundFolders.get());
        activity.runOnUiThread(() -> {
            // Update bottom indicator
            binding.loadingIndicatorText.setText(progressText);
            
            // Also update full-screen overlay if it's showing
            if (filterOverlayShowing.get()) {
                binding.cLLoading.txtProgress.setText(progressText);
            }
        });
    }

    private void incrementFiles(int amount) {
        foundFiles.addAndGet(amount);
    }

    private void incrementFolders(int amount) {
        foundFolders.addAndGet(amount);
    }
    
    /**
     * Check if password is valid for a folder, using cache to avoid redundant checks.
     * @param parentUri The parent folder URI to use as cache key
     * @param fileToCheck The file to check password against
     * @return true if password is valid, false otherwise
     */
    private boolean isPasswordValidForFolder(Context context, Uri parentUri, GalleryFile fileToCheck) {
        String cacheKey = parentUri.toString();
        
        // Check cache first
        Boolean cachedResult = passwordVerifiedCache.get(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
        }
        
        // Not in cache, perform actual check
        boolean isValid = true;
        try {
            char[] password = Password.getInstance().getPassword();
            Encryption.checkPassword(context, fileToCheck.getUri(), password, fileToCheck.getVersion(), false);
        } catch (InvalidPasswordException e) {
            isValid = false;
        } catch (Exception e) {
            SecureLog.e(TAG, "Error checking password for folder", e);
            isValid = false;
        }
        
        // Cache the result
        passwordVerifiedCache.put(cacheKey, isValid);
        return isValid;
    }

}