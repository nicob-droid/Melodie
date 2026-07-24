package com.melodie.player.ui.library;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.melodie.player.R;
import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.ui.adapter.FolderSourceAdapter;

import com.google.android.material.button.MaterialButton;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class FoldersFragment extends Fragment {

    private FoldersViewModel viewModel;
    private FolderSourceAdapter adapter;
    private TextView emptyView;
    private MaterialButton removeAllButton;
    private ActivityResultLauncher<Uri> addFolderLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_folders, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this).get(FoldersViewModel.class);

        ImageButton backButton = view.findViewById(R.id.btn_back);
        if (backButton != null) {
            backButton.setOnClickListener(v ->
                    NavHostFragment.findNavController(this).navigateUp());
        }

        RecyclerView recyclerView = view.findViewById(R.id.folders_recycler);
        emptyView = view.findViewById(R.id.folders_empty);
        MaterialButton addButton = view.findViewById(R.id.btn_add_source);
        removeAllButton = view.findViewById(R.id.btn_remove_all_sources);

        adapter = new FolderSourceAdapter(new FolderSourceAdapter.OnFolderSourceActionListener() {
            @Override
            public void onEnabledChanged(FolderSource source, boolean enabled) {
                viewModel.setFolderSourceEnabled(source, enabled);
            }

            @Override
            public void onRemoveClicked(FolderSource source) {
                confirmRemoveSource(source);
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        addFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null) return;
                    try {
                        final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
                    } catch (Exception ignored) {
                        // Certaines ROM gèrent déjà la persistance; on continue même si la prise échoue.
                    }

                    DocumentFile tree = DocumentFile.fromTreeUri(requireContext(), uri);
                    String displayName = tree != null && tree.getName() != null
                            ? tree.getName()
                            : (uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "Folder");
                    viewModel.addFolderSource(displayName, uri.toString());
                });

        addButton.setOnClickListener(v -> addFolderLauncher.launch(null));
        // Source Google Drive désactivée (accès restreint sans domaine vérifié).
        // Le bouton correspondant est masqué dans le layout ; on ne branche donc
        // plus aucune navigation vers l'écran Drive.
        if (removeAllButton != null) {
            removeAllButton.setOnClickListener(v -> confirmRemoveAllSources());
        }


        viewModel.getFolderSources().observe(getViewLifecycleOwner(), this::submitSources);
    }


    private void submitSources(List<FolderSource> sources) {
        adapter.submitList(sources);
        boolean empty = sources == null || sources.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (removeAllButton != null) {
            removeAllButton.setEnabled(!empty);
        }
    }

    private void confirmRemoveAllSources() {
        if (!isAdded()) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.folders_remove_all_confirm_title)
                .setMessage(R.string.folders_remove_all_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.folders_remove_all_confirm_yes, (dialog, which) ->
                        viewModel.removeAllFolderSources())
                .show();
    }

    private void confirmRemoveSource(FolderSource source) {
        if (source == null || !isAdded()) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.folders_remove_confirm_title)
                .setMessage(getString(R.string.folders_remove_confirm_message, source.displayName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.folders_remove_confirm_yes, (dialog, which) ->
                        viewModel.removeFolderSource(source))
                .show();
    }
}
