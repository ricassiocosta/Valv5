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
    // Cooperative cancellation flag for background loading tasks
    private final AtomicBoolean loadingCancelled = new AtomicBoolean(false);
    
    // Thread-safe list of all files found (for when user changes filter)
    private final List<GalleryFile> allFilesFound = Collections.synchronizedList(new UniqueLinkedList<>());
    
    // Pending files batch to add to UI
    private final List<GalleryFile> pendingBatch = Collections.synchronizedList(new ArrayList<>());
    
    // Track the active filter during lazy loading
    private int activeFilter = FILTER_ALL;
    
    private ExecutorService executorService;
    private Thread mainScanThread;
    // Thread used for index loading so we can manage its lifecycle
    private Thread indexLoadThread;

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
            // Start autosave for the index (will use weak reference to activity)
            FragmentActivity act = getActivity();
            if (act != null) {
                IndexManager.getInstance().startAutoSave(act);
            }
            
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
        
        // Load index in background to not block UI (cooperatively cancelable)
        indexLoadThread = new Thread(() -> {
            try {
                if (loadingCancelled.get() || !isAdded()) return;
                IndexManager indexManager = IndexManager.getInstance();
                boolean loaded = indexManager.loadIndex(activity, rootUri, password);
                if (loaded && !loadingCancelled.get() && isAdded()) {
                    SecureLog.d(TAG, "Index loaded successfully with " + indexManager.getEntryCount() + " entries");
                }
            } finally {
                // Clear reference when done
                indexLoadThread = null;
            }
        });
        indexLoadThread.start();
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
        // Signal cooperative cancellation for loading tasks
        loadingCancelled.set(true);
        // Cancel any pending filter wait
        filterWaitCancelled.set(true);
        if (filterWaitThread != null) {
            filterWaitThread.interrupt();
            filterWaitThread = null;
        }
        // Shutdown executor and clear cache when view is destroyed
        shutdownExecutor();
        // Stop any autosave scheduled by IndexManager to avoid leaking activity
        IndexManager indexManager = IndexManager.getInstance();

        // If lazy loading was cancelled, persist any index entries generated so far.
        // This does NOT probe file types; it only saves the in-memory index cache.
        try {
            if (indexManager.isDirty()) {
                FragmentActivity act = getActivity();
                char[] pwd = Password.getInstance().getPassword();
                List<Uri> roots = settings.getGalleryDirectoriesAsUri(true);
                if (act != null && pwd != null && roots != null && !roots.isEmpty()) {
                    Uri rootToSave = roots.get(0);
                    Thread saveThread = new Thread(() -> {
                        try {
                            indexManager.saveIfDirty(act, rootToSave, pwd);
                        } catch (Exception e) {
                            SecureLog.e(TAG, "onDestroyView: error saving index after cancel", e);
                        }
                    }, "IndexSaveOnCancel");
                    saveThread.setDaemon(true);
                    saveThread.start();
                }
            }
        } catch (Exception e) {
            SecureLog.e(TAG, "onDestroyView: error scheduling index save", e);
        }

        indexManager.stopAutoSave();
        passwordVerifiedCache.clear();
        allFilesFound.clear();
        pendingBatch.clear();
    }
    
    private void shutdownExecutor() {
        // Signal cancellation to running tasks
        loadingCancelled.set(true);
        if (mainScanThread != null) {
            mainScanThread.interrupt();
            mainScanThread = null;
        }
        // Interrupt index loading thread if active
        if (indexLoadThread != null) {
            indexLoadThread.interrupt();
            indexLoadThread = null;
        }
        // Capture current executor to avoid races with the scan thread which may hold
        // a reference to the original executor while awaiting termination.
        ExecutorService ex = executorService;
        if (ex != null && !ex.isShutdown()) {
            ex.shutdownNow();
        }
        // Null out the field so future loads create a new executor
        executorService = null;
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
        if (activity == null || !isSafe() || loadingCancelled.get() || Thread.currentThread().isInterrupted()) {
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
        } else {
            // Random order or loading complete - proceed normally
            super.orderBy(order);
            this.orderBy = order;
        }
    }

    /**
     * Intercept filter menu selections so we can show a full-screen overlay
     * when the index file isn't present yet. The overlay will inform the user
     * to wait for loading to finish and that they may generate the index
     * from the root folder options. After loading completes, the filter is applied.
     */
    @Override
    public boolean onMenuItemSelected(@NonNull android.view.MenuItem menuItem) {
        int id = menuItem.getItemId();
        int targetFilter = -1;
        if (id == R.id.filter_all) targetFilter = FILTER_ALL;
        else if (id == R.id.filter_images) targetFilter = FILTER_IMAGES;
        else if (id == R.id.filter_gifs) targetFilter = FILTER_GIFS;
        else if (id == R.id.filter_videos) targetFilter = FILTER_VIDEOS;
        else if (id == R.id.filter_text) targetFilter = FILTER_TEXTS;

        if (targetFilter != -1) {
            IndexManager indexManager = IndexManager.getInstance();
            // If index isn't loaded yet, show overlay and wait (offer generate-index hint)
            if (!indexManager.isLoaded() && isLoadingInProgress()) {
                showFilterLoadingOverlayForFilter(targetFilter);
                return true;
            }
        }

        // Fallback to base behaviour for other items or when index is present
        return super.onMenuItemSelected(menuItem);
    }

    /**
     * Similar to showFilterLoadingOverlay but targeted for filter actions.
     * Shows a full-screen overlay with cancel button and a hint about generating
     * the index from the root folder. After waiting for loading to finish, the
     * requested filter is applied.
     */
    public void showFilterLoadingOverlayForFilter(int targetFilter) {
        FragmentActivity activity = getActivity();
        if (activity == null || !isSafe() || loadingCancelled.get() || Thread.currentThread().isInterrupted()) {
            return;
        }

        filterWaitCancelled.set(false);
        filterOverlayShowing.set(true);

        activity.runOnUiThread(() -> {
            // Hide bottom indicator - only one should be visible at a time
            binding.loadingIndicatorBottom.setVisibility(View.GONE);

            // Show full overlay with an additional hint about index generation
            binding.cLLoading.txtLoading.setText(R.string.gallery_loading_finalizing);
            String progress = getString(R.string.gallery_loading_all_progress, foundFiles.get(), foundFolders.get());
            String hint = "\n\n" + getString(R.string.menu_generate_index) + ": available from root folder options to avoid waiting.";
            binding.cLLoading.txtProgress.setText(progress + hint);
            binding.cLLoading.txtProgress.setVisibility(View.VISIBLE);
            binding.cLLoading.btnCancelLoading.setVisibility(View.VISIBLE);
            binding.cLLoading.cLLoading.setVisibility(View.VISIBLE);

            // Setup cancel button
            binding.cLLoading.btnCancelLoading.setOnClickListener(v -> {
                // Cancel waiting and revert to no filter
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

                // Revert active filter to ALL
                activeFilter = FILTER_ALL;
                // Notify UI to reflect no filter
                DirectoryAllFragment.super.filterBy(FILTER_ALL);
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

            // Apply the filter now that loading is complete
            DirectoryAllFragment.super.filterBy(targetFilter);
        });
        filterWaitThread.start();
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

        // Shutdown any previous executor and create a new one for this load
        shutdownExecutor();
        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        // Capture a local reference for the scan thread to avoid races if shutdownExecutor
        // is called concurrently (onDestroyView). The localExecutor will remain valid
        // for the duration of this scan even if the field is nulled by the UI thread.
        final ExecutorService localExecutor = executorService;

        // Reset cancellation flag for this load
        loadingCancelled.set(false);

        mainScanThread = new Thread(() -> {
            long start = System.currentTimeMillis();
            List<Uri> directories = settings.getGalleryDirectoriesAsUri(true);
            FragmentActivity activity = getActivity();
            if (activity == null || !isSafe() || loadingCancelled.get() || Thread.currentThread().isInterrupted()) {
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
                if (!isSafe() || loadingCancelled.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (galleryFile.isDirectory()) {
                    localExecutor.submit(() -> {
                        if (!isSafe() || loadingCancelled.get() || Thread.currentThread().isInterrupted()) return;
                        findAllFilesInFolderLazy(galleryFile.getUri());
                    });
                }
            }
            
            // Wait for all folder scans to complete using the local executor reference
            localExecutor.shutdown();
            try {
                if (!localExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
                    SecureLog.w(TAG, "findAllFilesLazy: timeout waiting for tasks");
                    localExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                SecureLog.e(TAG, "findAllFilesLazy: interrupted", e);
                localExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Flush any remaining pending files
            if (!loadingCancelled.get()) flushPendingBatch();

            loadingComplete.set(true);
            SecureLog.d(TAG, "findAllFilesLazy: complete, found " + SecureLog.safeCount(allFilesFound.size(), "files") + ", took " + (System.currentTimeMillis() - start) + "ms");

            // After the lazy scan completes, persist index if there are unsaved entries.
            // Run on background thread to avoid blocking UI. Requires valid password and root URI.
            try {
                IndexManager indexManager = IndexManager.getInstance();
                if (indexManager.isDirty()) {
                    char[] pwd = Password.getInstance().getPassword();
                    List<Uri> roots = settings.getGalleryDirectoriesAsUri(true);
                    if (pwd != null && roots != null && !roots.isEmpty()) {
                        Uri rootUriToSave = roots.get(0);
                        Thread saveThread = new Thread(() -> {
                            try {
                                FragmentActivity saveAct = getActivity();
                                if (saveAct == null) return;
                                indexManager.saveIfDirty(saveAct, rootUriToSave, pwd);
                            } catch (Exception e) {
                                SecureLog.e(TAG, "findAllFilesLazy: error saving index after scan", e);
                            }
                        }, "IndexSaveAfterScan");
                        saveThread.setDaemon(true);
                        saveThread.start();
                    }
                }
            } catch (Exception e) {
                SecureLog.e(TAG, "findAllFilesLazy: error scheduling final save", e);
            }

            if (!isSafe() || loadingCancelled.get()) {
                return;
            }

            activity.runOnUiThread(() -> {
                if (!isAdded() || loadingCancelled.get()) return;
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
                if (loadingCancelled.get() || Thread.currentThread().isInterrupted() || !isAdded()) return;
                if (!isPasswordValidForFolder(activity, uri, fileToCheck)) {
                    filesInFolder.removeIf(f -> !f.isDirectory());
                }
            }
        }

        List<GalleryFile> filesFound = new ArrayList<>();
        List<GalleryFile> subFolders = new ArrayList<>();
        
        for (GalleryFile galleryFile : filesInFolder) {
            if (!isSafe() || loadingCancelled.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (galleryFile.isDirectory()) {
                subFolders.add(galleryFile);
            } else {
                filesFound.add(galleryFile);
            }
        }

        // Add files from this folder to UI
        if (!filesFound.isEmpty() && !loadingCancelled.get() && isAdded()) {
            addFilesToUIIncremental(filesFound);
        }

        // Update progress
        if (!loadingCancelled.get() && isAdded()) {
            activity.runOnUiThread(this::updateLoadingProgress);
        }

        // Recurse into subfolders
        for (GalleryFile subFolder : subFolders) {
            if (!isSafe() || loadingCancelled.get() || Thread.currentThread().isInterrupted()) {
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
        if (newFiles.isEmpty() || !isSafe() || loadingCancelled.get()) {
            return;
        }
        
        // Add to master list (all files, unfiltered)
        allFilesFound.addAll(newFiles);
        incrementFiles(newFiles.size());
        
        // Filter files based on active filter before adding to UI
        List<GalleryFile> filesToAdd = new ArrayList<>();
        List<GalleryFile> filesToHide = new ArrayList<>();
        
        IndexManager indexManager = IndexManager.getInstance();

        FragmentActivity activity = getActivity();
        char[] sessionPwd = Password.getInstance().getPassword();
        final boolean canProbe = activity != null && sessionPwd != null;

        for (GalleryFile f : newFiles) {
            int fileType = getFileTypeFromIndexOrFile(f);

            // If the index doesn't have this entry yet, try to probe the file type using the
            // session password and persist only when a real type could be determined.
            try {
                if (!f.isDirectory()) {
                    String encName = f.getEncryptedName();
                    Uri fu = f.getUri();
                    if (encName != null && indexManager.getType(encName) == -1 && fu != null) {
                        int probed = -1;
                        if (canProbe) {
                            try {
                                probed = indexManager.probeFileType(activity, fu, sessionPwd);
                            } catch (Exception e) {
                                SecureLog.e(TAG, "addFilesToUIIncremental: probeFileType failed", e);
                                probed = -1;
                            }
                        }

                        if (probed >= 0) {
                            // Use probed type and persist entry
                            fileType = probed;
                            String folderPath = FileStuff.getNestedPathFromUri(fu);
                            indexManager.addEntry(encName, fileType, folderPath != null ? folderPath : "");
                        } else {
                            // No reliable type could be probed; skip persisting now
                        }
                    }
                }
            } catch (Exception e) {
                SecureLog.e(TAG, "addFilesToUIIncremental: error adding index entry", e);
            }

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
        if (!filesToHide.isEmpty() && !loadingCancelled.get() && isAdded()) {
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
        if (activity == null || !isSafe() || loadingCancelled.get()) {
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
            if (!isSafe() || loadingCancelled.get()) {
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
        if (!isSafe() || loadingCancelled.get()) {
            return;
        }
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }
        final String progressText = getString(R.string.gallery_loading_all_progress, foundFiles.get(), foundFolders.get());
        activity.runOnUiThread(() -> {
            // Update bottom indicator
            if (!isSafe() || loadingCancelled.get()) return;
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