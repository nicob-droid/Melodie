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
public class AlbumSongsFragment extends Fragment {

    public static final String ARG_ALBUM_ID = "album_id";
    public static final String ARG_ALBUM_NAME = "album_name";

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
        long albumId = requireArguments().getLong(ARG_ALBUM_ID, -1L);
        String albumName = requireArguments().getString(ARG_ALBUM_NAME, "");
        if (albumName == null || albumName.trim().isEmpty()) {
            albumName = getString(R.string.unknown_album);
        }

        TextView title = view.findViewById(R.id.title);
        title.setText(getString(R.string.library_album_tracks_title, albumName));

        RecyclerView rv = view.findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        SongAdapter adapter = new SongAdapter((song, position) -> {
            playerController.playQueue(((SongAdapter) rv.getAdapter()).getCurrentList(), position);
            NavHostFragment.findNavController(AlbumSongsFragment.this)
                    .navigate(R.id.playerFragment);
        });
        rv.setAdapter(adapter);

        LibraryViewModel vm = new ViewModelProvider(this).get(LibraryViewModel.class);
        vm.songsByAlbum(albumId).observe(getViewLifecycleOwner(), adapter::submitList);
    }
}


