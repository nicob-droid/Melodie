package com.melodie.player.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.ui.adapter.LibraryAlbumListAdapter;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class AlbumsFragment extends Fragment {

    private TextView emptyStateView;
    private int lastAlbumCount;
    private boolean hasActiveSources = true;
    private LibraryViewModel viewModel;
    private LinearLayoutManager layoutManager;
    private boolean pendingStateRestore;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_albums, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView rv = view.findViewById(R.id.recycler);
        emptyStateView = view.findViewById(R.id.albums_empty_state);
        layoutManager = new LinearLayoutManager(requireContext());
        rv.setLayoutManager(layoutManager);
        LibraryViewModel vm = new ViewModelProvider(requireParentFragment())
                .get(LibraryViewModel.class);
        viewModel = vm;
        // Une restauration est en attente si on dispose d'un état sauvegardé.
        pendingStateRestore = vm.albumsListState != null;
        LibraryAlbumListAdapter adapter = new LibraryAlbumListAdapter(
                album -> {
                    Bundle args = new Bundle();
                    args.putLong(AlbumSongsFragment.ARG_ALBUM_ID, album.id);
                    args.putString(AlbumSongsFragment.ARG_ALBUM_NAME, album.name);
                    NavHostFragment.findNavController(AlbumsFragment.this)
                            .navigate(R.id.albumSongsFragment, args);
                },
                album -> vm.resolveAlbumCover(album, true)
        );
        rv.setAdapter(adapter);

        vm.albums().observe(getViewLifecycleOwner(), albums -> {
            adapter.submitList(albums, () -> {
                // Restaure la position de défilement une seule fois, après que la liste
                // diffée soit réellement appliquée au RecyclerView.
                if (pendingStateRestore && layoutManager != null && vm.albumsListState != null) {
                    layoutManager.onRestoreInstanceState(vm.albumsListState);
                    pendingStateRestore = false;
                }
            });
            lastAlbumCount = albums != null ? albums.size() : 0;
            updateEmptyState();
        });

        vm.folderSources().observe(getViewLifecycleOwner(), sources -> {
            boolean active = false;
            if (sources != null) {
                for (com.melodie.player.data.entity.FolderSource source : sources) {
                    if (source != null && source.enabled) {
                        active = true;
                        break;
                    }
                }
            }
            hasActiveSources = active;
            updateEmptyState();
        });
    }

    private void updateEmptyState() {
        if (emptyStateView == null) return;
        boolean showEmpty = lastAlbumCount == 0;
        emptyStateView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (!showEmpty) return;

        emptyStateView.setText(hasActiveSources
                ? R.string.albums_empty
                : R.string.albums_empty_no_active_source);
    }


    @Override
    public void onDestroyView() {
        // Sauvegarde la position de défilement avant la destruction de la vue
        // (navigation vers la liste des morceaux), pour la restaurer au retour.
        if (viewModel != null && layoutManager != null) {
            viewModel.albumsListState = layoutManager.onSaveInstanceState();
        }
        layoutManager = null;
        emptyStateView = null;
        super.onDestroyView();
    }
}


