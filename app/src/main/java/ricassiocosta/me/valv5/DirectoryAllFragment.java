package ricassiocosta.me.valv5;

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
import java.util.List;

import ricassiocosta.me.valv5.data.GalleryFile;
import ricassiocosta.me.valv5.data.Password;
import ricassiocosta.me.valv5.data.UniqueLinkedList;
import ricassiocosta.me.valv5.encryption.Encryption;
import ricassiocosta.me.valv5.exception.InvalidPasswordException;
import ricassiocosta.me.valv5.utils.FileStuff;
import ricassiocosta.me.valv5.utils.Settings;

public class DirectoryAllFragment extends DirectoryBaseFragment {
    private static final String TAG = "DirectoryAllFragment";

    private int foundFiles = 0, foundFolders = 0;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.layoutFabsAdd.setVisibility(View.GONE);
        binding.noMedia.setVisibility(View.GONE);
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

        galleryViewModel.setOnAdapterItemChanged(pos -> {
            galleryPagerAdapter.notifyItemChanged(pos);
            galleryGridAdapter.notifyItemChanged(pos);
        });

        setupViewpager();
        setupGrid();
        setClickListeners();

        if (!galleryViewModel.isInitialised()) {
            findAllFiles();
        }

        initViewModels();
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

    private void findAllFiles() {
        SecureLog.d(TAG, "findAllFiles: start");
        foundFiles = 0;
        foundFolders = 0;
        setLoading(true);
        new Thread(() -> {
            List<Uri> directories = settings.getGalleryDirectoriesAsUri(true);
            FragmentActivity activity = getActivity();
            if (activity == null || !isSafe()) {
                return;
            }
            List<Uri> uriFiles = new ArrayList<>(directories.size());
            for (Uri uri : directories) {
                DocumentFile documentFile = DocumentFile.fromTreeUri(activity, uri);
                if (documentFile.canRead()) {
                    uriFiles.add(documentFile.getUri());
                }
            }

            activity.runOnUiThread(this::setLoadingAllWithProgress);

            List<GalleryFile> folders = new ArrayList<>();
            List<GalleryFile> files = new UniqueLinkedList<>();
            long start = System.currentTimeMillis();
            List<GalleryFile> filesToSearch = new ArrayList<>();
            for (Uri uri : uriFiles) {
                List<GalleryFile> filesInFolder = FileStuff.getFilesInFolder(activity, uri, true);
                for (GalleryFile foundFile : filesInFolder) {
                    if (foundFile.isDirectory()) {
                        SecureLog.d(TAG, "findAllFiles: found directory");
                        boolean add = true;
                        for (GalleryFile addedFile : filesToSearch) {
                            if (foundFile.getNameWithPath().startsWith(addedFile.getNameWithPath() + "/")) {
                                // Do not add e.g. folder Pictures/a/b if folder Pictures/a have already been added as it will be searched by a thread in findAllFilesInFolder().
                                // Prevents showing duplicate files
                                add = false;
                                SecureLog.d(TAG, "findAllFiles: not adding nested directory");
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
                    files.add(galleryFile);
                }
            }

            incrementFiles(files.size());

            activity.runOnUiThread(this::setLoadingAllWithProgress);

            List<Thread> threads = new ArrayList<>();
            for (GalleryFile galleryFile : folders) {
                if (galleryFile.isDirectory()) {
                    Thread t = new Thread(() -> {
                        List<GalleryFile> allFilesInFolder = findAllFilesInFolder(galleryFile.getUri());
                        synchronized (LOCK) {
                            files.addAll(allFilesInFolder);
                        }
                    });
                    threads.add(t);
                    t.start();
                }
            }
            for (Thread t : threads) {
                if (!isSafe()) {
                    return;
                }
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            SecureLog.d(TAG, "findAllFiles: joined, found " + SecureLog.safeCount(files.size(), "files") + ", took " + (System.currentTimeMillis() - start) + "ms");
            if (!isSafe()) {
                return;
            }

            files.sort(GalleryFile::compareTo);

            activity.runOnUiThread(() -> {
                setLoading(false);
                if (files.size() > MIN_FILES_FOR_FAST_SCROLL) {
                    binding.recyclerView.setFastScrollEnabled(true);
                }
                if (galleryViewModel.isInitialised()) {
                    return;
                }
                galleryViewModel.addGalleryFiles(files);
                galleryViewModel.setInitialised(true);
                galleryGridAdapter.notifyItemRangeInserted(0, files.size());
                galleryPagerAdapter.notifyItemRangeInserted(0, files.size());
            });
        }).start();
    }

    private void setLoadingAllWithProgress() {
        if (!isSafe()) {
            return;
        }
        binding.cLLoading.cLLoading.setVisibility(View.VISIBLE);
        binding.cLLoading.txtProgress.setText(getString(R.string.gallery_loading_all_progress, foundFiles, foundFolders));
        binding.cLLoading.txtProgress.setVisibility(View.VISIBLE);
    }

    private synchronized void incrementFiles(int amount) {
        foundFiles += amount;
    }

    private synchronized void incrementFolders(int amount) {
        foundFolders += amount;
    }

    @NonNull
    private List<GalleryFile> findAllFilesInFolder(Uri uri) {
        SecureLog.d(TAG, "findAllFilesInFolder: scanning folder");
        List<GalleryFile> files = new UniqueLinkedList<>();
        FragmentActivity activity = getActivity();
        if (activity == null || !isSafe()) {
            return files;
        }
        incrementFolders(1);
        List<GalleryFile> filesInFolder = FileStuff.getFilesInFolder(activity, uri, true);

        // Password check logic starts here
        if (!filesInFolder.isEmpty()) {
            GalleryFile fileToCheck = null;
            for (GalleryFile f : filesInFolder) {
                if (!f.isDirectory()) {
                    fileToCheck = f;
                    break;
                }
            }

            if (fileToCheck != null) {
                try {
                    char[] password = Password.getInstance().getPassword();
                    Encryption.checkPassword(activity, fileToCheck.getUri(), password, fileToCheck.getVersion(), false);
                } catch (InvalidPasswordException e) {
                    // Password is wrong for this folder, filter out files
                    filesInFolder.removeIf(f -> !f.isDirectory());
                } catch (Exception e) {
                    SecureLog.e(TAG, "Error checking password for folder", e);
                    // Treat as wrong password
                    filesInFolder.removeIf(f -> !f.isDirectory());
                }
            }
        }
        // Password check logic ends here

        for (GalleryFile galleryFile : filesInFolder) {
            if (!isSafe()) {
                return files;
            }
            if (galleryFile.isDirectory()) {
                activity.runOnUiThread(this::setLoadingAllWithProgress);
                files.addAll(findAllFilesInFolder(galleryFile.getUri()));
            } else {
                files.add(galleryFile);
            }
        }
        incrementFiles(files.size());
        activity.runOnUiThread(this::setLoadingAllWithProgress);
        return files;
    }

}