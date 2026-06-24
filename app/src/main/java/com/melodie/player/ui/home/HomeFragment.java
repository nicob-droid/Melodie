package com.melodie.player.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.ui.adapter.AlbumAdapter;
import com.melodie.player.ui.adapter.SongAdapter;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class HomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView rvAlbums = view.findViewById(R.id.rv_recent_albums);
        rvAlbums.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        AlbumAdapter albumAdapter = new AlbumAdapter(a -> { }, a -> { });
        rvAlbums.setAdapter(albumAdapter);

        RecyclerView rvFav = view.findViewById(R.id.rv_favorites);
        rvFav.setLayoutManager(new LinearLayoutManager(requireContext()));
        SongAdapter favAdapter = new SongAdapter((s, p) -> { });
        rvFav.setAdapter(favAdapter);

        HomeViewModel vm = new ViewModelProvider(this).get(HomeViewModel.class);
        vm.recentAlbums().observe(getViewLifecycleOwner(), albumAdapter::submitList);
        vm.favorites().observe(getViewLifecycleOwner(), favAdapter::submitList);
    }
}

