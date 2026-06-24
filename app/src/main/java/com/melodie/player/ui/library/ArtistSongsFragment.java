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
import com.melodie.player.playback.PlayerController;
import com.melodie.player.ui.adapter.SongAdapter;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ArtistSongsFragment extends Fragment {

    public static final String ARG_ARTIST_NAME = "artist_name";

    @Inject
    PlayerController playerController;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_filtered_songs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        String artistName = requireArguments().getString(ARG_ARTIST_NAME, "");
        if (artistName == null || artistName.trim().isEmpty()) {
            artistName = getString(R.string.unknown_artist);
        }

        TextView title = view.findViewById(R.id.title);
        title.setText(getString(R.string.library_artist_tracks_title, artistName));

        RecyclerView rv = view.findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        SongAdapter adapter = new SongAdapter((song, position) -> {
            playerController.playQueue(((SongAdapter) rv.getAdapter()).getCurrentList(), position);
            NavHostFragment.findNavController(ArtistSongsFragment.this)
                    .navigate(R.id.playerFragment);
        });
        rv.setAdapter(adapter);

        LibraryViewModel vm = new ViewModelProvider(this).get(LibraryViewModel.class);
        vm.songsByArtist(artistName).observe(getViewLifecycleOwner(), adapter::submitList);
    }
}


