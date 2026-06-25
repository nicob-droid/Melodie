package com.melodie.player.ui.equalizer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.melodie.player.R;
import com.melodie.player.playback.PlayerController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class EqualizerFragment extends Fragment {

    private static final int[] BAND_VIEW_IDS = {
            R.id.band_0,
            R.id.band_1,
            R.id.band_2,
            R.id.band_3,
            R.id.band_4
    };

    private static final int[] BAND_LEVEL_IDS = {
            R.id.band_0_level,
            R.id.band_1_level,
            R.id.band_2_level,
            R.id.band_3_level,
            R.id.band_4_level
    };

    @Inject
    PlayerController playerController;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Spinner presetSpinner;
    private TextView unavailable;
    private final VerticalSeekBar[] bandBars = new VerticalSeekBar[BAND_VIEW_IDS.length];
    private final TextView[] bandLevelLabels = new TextView[BAND_LEVEL_IDS.length];
    private SwitchMaterial bassSwitch;
    private SeekBar bassStrength;
    private SwitchMaterial virtualizerSwitch;
    private SeekBar virtualizerStrength;
    private SwitchMaterial loudnessSwitch;
    private VerticalSeekBar masterOut;
    private TextView masterOutLevel;

    private boolean presetSelectionGuard;

    private final Runnable delayedInit = this::bindUiToEngine;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_equalizer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        presetSpinner = view.findViewById(R.id.preset_spinner);
        unavailable = view.findViewById(R.id.eq_unavailable);
        bassSwitch = view.findViewById(R.id.switch_bass_boost);
        bassStrength = view.findViewById(R.id.seek_bass_boost);
        virtualizerSwitch = view.findViewById(R.id.switch_virtualizer);
        virtualizerStrength = view.findViewById(R.id.seek_virtualizer);
        loudnessSwitch = view.findViewById(R.id.switch_loudness);
        masterOut = view.findViewById(R.id.band_master);
        masterOutLevel = view.findViewById(R.id.band_master_level);

        for (int i = 0; i < BAND_VIEW_IDS.length; i++) {
            bandBars[i] = view.findViewById(BAND_VIEW_IDS[i]);
            bandLevelLabels[i] = view.findViewById(BAND_LEVEL_IDS[i]);
        }

        playerController.init();
        setupListeners();
        bindUiToEngine();
    }

    @Override
    public void onResume() {
        super.onResume();
        bindUiToEngine();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(delayedInit);
    }

    private void setupListeners() {
        for (int i = 0; i < bandBars.length; i++) {
            final short band = (short) i;
            final TextView levelLabel = bandLevelLabels[i];
            bandBars[i].setOnProgressChangeListener((seekBar, progress, fromUser) -> {
                if (fromUser) {
                    short min = playerController.getBandLevelMin();
                    playerController.setBandLevel(band, (short) (min + progress));
                }
                updateBandLevelLabel(levelLabel, seekBar, playerController.getBandLevelMin());
            });
        }

        masterOut.setOnProgressChangeListener((seekBar, progress, fromUser) -> {
            if (fromUser) playerController.setLoudnessGainMb(progress);
            masterOutLevel.setText(String.format(Locale.US, "%.1f", progress / 100f));
        });

        bassSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                playerController.setBassBoostEnabled(isChecked));
        virtualizerSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                playerController.setVirtualizerEnabled(isChecked));
        loudnessSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                playerController.setLoudnessEnabled(isChecked));

        bassStrength.setOnSeekBarChangeListener(simpleProgressListener(
                value -> playerController.setBassBoostStrength((short) value)));
        virtualizerStrength.setOnSeekBarChangeListener(simpleProgressListener(
                value -> playerController.setVirtualizerStrength((short) value)));

        presetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (presetSelectionGuard || position == 0) return;
                playerController.useEqualizerPreset((short) (position - 1));
                refreshBandLevels();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
    }

    private void updateBandLevelLabel(TextView label, VerticalSeekBar bar, short min) {
        // Convert progress back to dB: level in millibels → dB
        float db = (min + bar.getProgress()) / 100f;
        label.setText(String.format(Locale.US, "%+.1f", db));
    }

    private SeekBar.OnSeekBarChangeListener simpleProgressListener(IntConsumer onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) onChange.accept(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }

    private void bindUiToEngine() {
        boolean ready = playerController.isEqualizerAvailable();
        setControlsEnabled(ready);
        unavailable.setVisibility(ready ? View.GONE : View.VISIBLE);

        if (!ready) {
            handler.removeCallbacks(delayedInit);
            handler.postDelayed(delayedInit, 1200);
            return;
        }

        bindPresets();
        refreshBandLevels();
        bassSwitch.setChecked(playerController.isBassBoostEnabled());
        bassStrength.setProgress(playerController.getBassBoostStrength());
        virtualizerSwitch.setChecked(playerController.isVirtualizerEnabled());
        virtualizerStrength.setProgress(playerController.getVirtualizerStrength());
        loudnessSwitch.setChecked(playerController.isLoudnessEnabled());
        masterOut.setProgress(playerController.getLoudnessGainMb());
        masterOutLevel.setText(String.format(Locale.US, "%.1f", playerController.getLoudnessGainMb() / 100f));
    }

    private void bindPresets() {
        List<String> presetNames = new ArrayList<>();
        presetNames.add(getString(R.string.eq_preset_custom));
        short count = playerController.getEqualizerPresetCount();
        for (short i = 0; i < count; i++) {
            String name = playerController.getEqualizerPresetName(i);
            presetNames.add(name != null ? name : ("Preset " + i));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, presetNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        presetSelectionGuard = true;
        presetSpinner.setSelection(0, false);
        presetSelectionGuard = false;
    }

    private void refreshBandLevels() {
        short min = playerController.getBandLevelMin();
        short max = playerController.getBandLevelMax();
        int seekMax = max - min;
        short bandCount = playerController.getEqualizerBandCount();

        for (short band = 0; band < bandBars.length; band++) {
            VerticalSeekBar bar = bandBars[band];
            boolean exists = band < bandCount;
            bar.setEnabled(exists);
            if (!exists) {
                bar.setProgress(0);
                bandLevelLabels[band].setText("0.0");
                continue;
            }
            bar.setMax(seekMax);
            int level = playerController.getBandLevel(band) - min;
            bar.setProgress(Math.max(0, Math.min(seekMax, level)));
            updateBandLevelLabel(bandLevelLabels[band], bar, min);
        }
    }

    private void setControlsEnabled(boolean enabled) {
        presetSpinner.setEnabled(enabled);
        for (VerticalSeekBar bar : bandBars) bar.setEnabled(enabled);
        bassSwitch.setEnabled(enabled);
        bassStrength.setEnabled(enabled);
        virtualizerSwitch.setEnabled(enabled);
        virtualizerStrength.setEnabled(enabled);
        loudnessSwitch.setEnabled(enabled);
        masterOut.setEnabled(enabled);
    }

    private interface IntConsumer {
        void accept(int value);
    }
}
