package com.melodie.player.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.melodie.player.R;
import com.melodie.player.ui.search.SearchFragment;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LibraryFragment extends Fragment {

    private static final int[] TABS = {
            R.string.tab_albums,
            R.string.tab_artists,
            R.string.nav_search,
            R.string.tab_favorites,
            R.string.tab_settings
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TabLayout tabs = view.findViewById(R.id.tabs);
        ViewPager2 pager = view.findViewById(R.id.pager);

        pager.setAdapter(new FragmentStateAdapter(this) {
            @Override
            public int getItemCount() { return TABS.length; }

            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return switch (position) {
                    case 0 -> new AlbumsFragment();
                    case 1 -> new ArtistsFragment();
                    case 2 -> new SearchFragment();
                    case 3 -> new PlaylistsFragment();
                    case 4 -> new LibrarySettingsFragment();
                    default -> new AlbumsFragment();
                };
            }
        });

        new TabLayoutMediator(tabs, pager,
                (tab, position) -> tab.setText(TABS[position])).attach();
    }
}
