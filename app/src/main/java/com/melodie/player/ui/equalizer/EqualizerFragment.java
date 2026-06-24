package com.melodie.player.ui.equalizer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.melodie.player.R;

/**
 * Placeholder 5-band equalizer UI. Wiring to {@link android.media.audiofx.Equalizer}
 * requires the ExoPlayer audio session id, which must be exposed from the
 * playback service in a future iteration.
 */
public class EqualizerFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_equalizer, container, false);
    }
}

