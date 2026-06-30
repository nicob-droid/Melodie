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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class AlbumsFragment extends Fragment {

    private final Set<Long> prefetchedAlbumIds = new HashSet<>();
    private TextView emptyStateView;
    private int lastAlbumCount;
    private boolean hasActiveSources = true;

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
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        LibraryViewModel vm = new ViewModelProvider(requireParentFragment())
                .get(LibraryViewModel.class);
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
            adapter.submitList(albums);
            lastAlbumCount = albums != null ? albums.size() : 0;
            updateEmptyState();
            // Prefetch covers only for albums we haven't prefetched yet.
            prefetchNewCovers(vm, albums);
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

    private void prefetchNewCovers(LibraryViewModel vm, List<com.melodie.player.data.entity.Album> albums) {
        if (albums == null) return;
        for (com.melodie.player.data.entity.Album album : albums) {
            if (album == null) continue;
            boolean missingCover = album.cover == null || album.cover.trim().isEmpty();
            boolean missingReleaseDate = album.releaseDate == null || album.releaseDate.trim().isEmpty();
            if ((missingCover || missingReleaseDate) && !prefetchedAlbumIds.contains(album.id)) {
                vm.resolveAlbumCover(album, false);
            }
            if (!missingCover && !missingReleaseDate) {
                prefetchedAlbumIds.add(album.id);
            }
        }
    }
}


