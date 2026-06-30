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
import com.melodie.player.ui.adapter.ArtistAdapter;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ArtistsFragment extends Fragment {

    private TextView emptyStateView;
    private int lastArtistCount;
    private boolean hasActiveSources = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_artists, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView rv = view.findViewById(R.id.recycler);
        emptyStateView = view.findViewById(R.id.artists_empty_state);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        ArtistAdapter adapter = new ArtistAdapter(artist -> {
            Bundle args = new Bundle();
            args.putString(ArtistSongsFragment.ARG_ARTIST_NAME, artist.name);
            NavHostFragment.findNavController(ArtistsFragment.this)
                    .navigate(R.id.artistSongsFragment, args);
        });
        rv.setAdapter(adapter);

        LibraryViewModel vm = new ViewModelProvider(requireParentFragment())
                .get(LibraryViewModel.class);
        vm.artistsWithData().observe(getViewLifecycleOwner(), artists -> {
            adapter.submitList(artists);
            lastArtistCount = artists != null ? artists.size() : 0;
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
        boolean showEmpty = lastArtistCount == 0;
        emptyStateView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (!showEmpty) return;

        emptyStateView.setText(hasActiveSources
                ? R.string.artists_empty
                : R.string.artists_empty_no_active_source);
    }
}

