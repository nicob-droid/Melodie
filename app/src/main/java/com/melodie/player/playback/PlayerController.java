package com.melodie.player.playback;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.melodie.player.data.db.SongDao;
import com.melodie.player.data.entity.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Wraps a {@link MediaController} bound to {@link PlaybackService}. Exposes
 * LiveData of the current song / playing state for the UI.
 */
@Singleton
public class PlayerController {

    private static final String PREFS_EQ = "melodie_eq";
    private static final String PREF_EQ_ENABLED = "eq_enabled";
    private static final String PREF_EQ_BAND_PREFIX = "eq_band_";
    private static final String PREF_BASS_ENABLED = "bass_enabled";
    private static final String PREF_BASS_STRENGTH = "bass_strength";
    private static final String PREF_VIRTUALIZER_ENABLED = "virtualizer_enabled";
    private static final String PREF_VIRTUALIZER_STRENGTH = "virtualizer_strength";
    private static final String PREF_LOUDNESS_ENABLED = "loudness_enabled";
    private static final String PREF_LOUDNESS_GAIN_MB = "loudness_gain_mb";

    private static final short DEFAULT_BASS_STRENGTH = 650;
    private static final short DEFAULT_VIRTUALIZER_STRENGTH = 650;
    private static final int DEFAULT_LOUDNESS_GAIN_MB = 700;

    private final Context context;
    private final SharedPreferences eqPrefs;
    private final SongDao songDao;
    private final ExecutorService executor;
    @Nullable
    private MediaController controller;

    public final MutableLiveData<Song> currentSong = new MutableLiveData<>(null);
    public final MutableLiveData<Boolean> isPlaying = new MutableLiveData<>(false);
    public final MutableLiveData<Boolean> isBuffering = new MutableLiveData<>(false);
    public final MutableLiveData<Long> position = new MutableLiveData<>(0L);
    public final MutableLiveData<Long> duration = new MutableLiveData<>(0L);

    private List<Song> queue = new ArrayList<>();

    @Nullable private Equalizer equalizer;
    @Nullable private BassBoost bassBoost;
    @Nullable private Virtualizer virtualizer;
    @Nullable private LoudnessEnhancer loudnessEnhancer;
    private int effectsSessionId = C.AUDIO_SESSION_ID_UNSET;

    @Inject
    public PlayerController(@ApplicationContext Context context,
                            SongDao songDao,
                            ExecutorService executor) {
        this.context = context;
        this.songDao = songDao;
        this.executor = executor;
        this.eqPrefs = context.getSharedPreferences(PREFS_EQ, Context.MODE_PRIVATE);
    }

    public void init() {
        if (controller != null) return;
        SessionToken token = new SessionToken(context,
                new ComponentName(context, PlaybackService.class));
        ListenableFuture<MediaController> future =
                new MediaController.Builder(context, token).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean playing) {
                        isPlaying.postValue(playing);
                    }

                    @Override
                    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                        updateCurrent();
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        isBuffering.postValue(state == Player.STATE_BUFFERING);
                        if (controller != null) {
                            long currentDuration = Math.max(0, controller.getDuration());
                            duration.postValue(currentDuration);
                            persistCurrentDurationIfKnown(currentDuration);
                        }
                    }
                });
                updateCurrent();
                ensureEffects();
            } catch (ExecutionException | InterruptedException ignored) {
            }
        }, command -> new Handler(Looper.getMainLooper()).post(command));
    }

    private void updateCurrent() {
        if (controller == null) return;
        int idx = controller.getCurrentMediaItemIndex();
        if (idx >= 0 && idx < queue.size()) {
            currentSong.postValue(queue.get(idx));
        } else {
            currentSong.postValue(null);
        }
        duration.postValue(Math.max(0, controller.getDuration()));
        isPlaying.postValue(controller.isPlaying());
    }

    public void playQueue(List<Song> songs, int startIndex) {
        if (controller == null || songs.isEmpty()) return;
        this.queue = new ArrayList<>(songs);
        List<MediaItem> items = new ArrayList<>(songs.size());
        for (Song s : songs) {
            items.add(toMediaItem(s));
        }
        controller.setMediaItems(items, startIndex, 0L);
        controller.prepare();
        controller.play();
    }

    public void togglePlay() {
        if (controller == null) return;
        if (controller.isPlaying()) controller.pause();
        else controller.play();
    }

    public void next() {
        if (controller != null) controller.seekToNext();
    }

    public void previous() {
        if (controller != null) controller.seekToPrevious();
    }

    public void seekTo(long ms) {
        if (controller != null) controller.seekTo(ms);
    }

    public void setShuffle(boolean shuffle) {
        if (controller != null) controller.setShuffleModeEnabled(shuffle);
    }

    public void setRepeatMode(int mode) {
        if (controller != null) controller.setRepeatMode(mode);
    }

    public long getPosition() {
        return controller != null ? Math.max(0, controller.getCurrentPosition()) : 0L;
    }

    public long getDuration() {
        return controller != null ? Math.max(0, controller.getDuration()) : 0L;
    }

    public boolean isReady() {
        return controller != null;
    }

    public int getAudioSessionId() {
        if (controller == null) return C.AUDIO_SESSION_ID_UNSET;
        int id = controller.getAudioSessionId();
        return id != C.AUDIO_SESSION_ID_UNSET ? id : C.AUDIO_SESSION_ID_UNSET;
    }

    public boolean isEqualizerAvailable() {
        return ensureEffects() && equalizer != null;
    }

    public short getEqualizerBandCount() {
        return equalizer != null ? equalizer.getNumberOfBands() : 0;
    }

    public short getEqualizerPresetCount() {
        return equalizer != null ? equalizer.getNumberOfPresets() : 0;
    }

    @Nullable
    public String getEqualizerPresetName(short preset) {
        if (equalizer == null) return null;
        return equalizer.getPresetName(preset);
    }

    public void useEqualizerPreset(short preset) {
        if (!ensureEffects() || equalizer == null) return;
        if (preset < 0 || preset >= getEqualizerPresetCount()) return;
        equalizer.usePreset(preset);
        short count = equalizer.getNumberOfBands();
        for (short band = 0; band < count; band++) {
            eqPrefs.edit().putInt(PREF_EQ_BAND_PREFIX + band, equalizer.getBandLevel(band)).apply();
        }
    }

    public int getEqualizerCenterFreqHz(short band) {
        if (equalizer == null) return 0;
        return equalizer.getCenterFreq(band) / 1000;
    }

    public short getBandLevelMin() {
        if (equalizer == null) return -1500;
        return equalizer.getBandLevelRange()[0];
    }

    public short getBandLevelMax() {
        if (equalizer == null) return 1500;
        return equalizer.getBandLevelRange()[1];
    }

    public short getBandLevel(short band) {
        if (equalizer == null) return 0;
        return equalizer.getBandLevel(band);
    }

    public void setBandLevel(short band, short level) {
        if (!ensureEffects() || equalizer == null) return;
        short clamped = clampShort(level, getBandLevelMin(), getBandLevelMax());
        equalizer.setBandLevel(band, clamped);
        eqPrefs.edit().putInt(PREF_EQ_BAND_PREFIX + band, clamped).apply();
    }

    public boolean isEqEnabled() {
        return eqPrefs.getBoolean(PREF_EQ_ENABLED, true);
    }

    public void setEqEnabled(boolean enabled) {
        eqPrefs.edit().putBoolean(PREF_EQ_ENABLED, enabled).apply();
        if (ensureEffects() && equalizer != null) {
            equalizer.setEnabled(enabled);
        }
    }

    public boolean isBassBoostEnabled() {
        return eqPrefs.getBoolean(PREF_BASS_ENABLED, false);
    }

    public void setBassBoostEnabled(boolean enabled) {
        eqPrefs.edit().putBoolean(PREF_BASS_ENABLED, enabled).apply();
        if (ensureEffects() && bassBoost != null) {
            bassBoost.setEnabled(enabled);
        }
    }

    public short getBassBoostStrength() {
        return (short) eqPrefs.getInt(PREF_BASS_STRENGTH, DEFAULT_BASS_STRENGTH);
    }

    public void setBassBoostStrength(short strength) {
        short clamped = clampShort(strength, (short) 0, (short) 1000);
        eqPrefs.edit().putInt(PREF_BASS_STRENGTH, clamped).apply();
        if (ensureEffects() && bassBoost != null) {
            bassBoost.setStrength(clamped);
        }
    }

    public boolean isVirtualizerEnabled() {
        return eqPrefs.getBoolean(PREF_VIRTUALIZER_ENABLED, false);
    }

    public void setVirtualizerEnabled(boolean enabled) {
        eqPrefs.edit().putBoolean(PREF_VIRTUALIZER_ENABLED, enabled).apply();
        if (ensureEffects() && virtualizer != null) {
            virtualizer.setEnabled(enabled);
        }
    }

    public short getVirtualizerStrength() {
        return (short) eqPrefs.getInt(PREF_VIRTUALIZER_STRENGTH, DEFAULT_VIRTUALIZER_STRENGTH);
    }

    public void setVirtualizerStrength(short strength) {
        short clamped = clampShort(strength, (short) 0, (short) 1000);
        eqPrefs.edit().putInt(PREF_VIRTUALIZER_STRENGTH, clamped).apply();
        if (ensureEffects() && virtualizer != null) {
            virtualizer.setStrength(clamped);
        }
    }

    public boolean isLoudnessEnabled() {
        return eqPrefs.getBoolean(PREF_LOUDNESS_ENABLED, false);
    }

    public void setLoudnessEnabled(boolean enabled) {
        eqPrefs.edit().putBoolean(PREF_LOUDNESS_ENABLED, enabled).apply();
        if (ensureEffects() && loudnessEnhancer != null) {
            loudnessEnhancer.setEnabled(enabled);
        }
    }

    public int getLoudnessGainMb() {
        return eqPrefs.getInt(PREF_LOUDNESS_GAIN_MB, DEFAULT_LOUDNESS_GAIN_MB);
    }

    public void setLoudnessGainMb(int gainMb) {
        int clamped = Math.max(0, Math.min(2000, gainMb));
        eqPrefs.edit().putInt(PREF_LOUDNESS_GAIN_MB, clamped).apply();
        if (ensureEffects() && loudnessEnhancer != null) {
            loudnessEnhancer.setTargetGain(clamped);
        }
    }

    private boolean ensureEffects() {
        int sessionId = getAudioSessionId();
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return false;

        if (sessionId == effectsSessionId && equalizer != null) return true;

        releaseEffects();
        try {
            equalizer = new Equalizer(0, sessionId);
            bassBoost = new BassBoost(0, sessionId);
            virtualizer = new Virtualizer(0, sessionId);
            loudnessEnhancer = new LoudnessEnhancer(sessionId);

            effectsSessionId = sessionId;
            applyStoredEffectSettings();
            return true;
        } catch (RuntimeException e) {
            releaseEffects();
            return false;
        }
    }

    private void applyStoredEffectSettings() {
        if (equalizer != null) {
            short count = equalizer.getNumberOfBands();
            short min = getBandLevelMin();
            short max = getBandLevelMax();
            for (short band = 0; band < count; band++) {
                int stored = eqPrefs.getInt(PREF_EQ_BAND_PREFIX + band, 0);
                equalizer.setBandLevel(band, clampShort((short) stored, min, max));
            }
            equalizer.setEnabled(isEqEnabled());
        }
        if (bassBoost != null) {
            bassBoost.setStrength(getBassBoostStrength());
            bassBoost.setEnabled(isBassBoostEnabled());
        }
        if (virtualizer != null) {
            virtualizer.setStrength(getVirtualizerStrength());
            virtualizer.setEnabled(isVirtualizerEnabled());
        }
        if (loudnessEnhancer != null) {
            loudnessEnhancer.setTargetGain(getLoudnessGainMb());
            loudnessEnhancer.setEnabled(isLoudnessEnabled());
        }
    }

    private static short clampShort(short value, short min, short max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void releaseEffects() {
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        if (bassBoost != null) {
            bassBoost.release();
            bassBoost = null;
        }
        if (virtualizer != null) {
            virtualizer.release();
            virtualizer = null;
        }
        if (loudnessEnhancer != null) {
            loudnessEnhancer.release();
            loudnessEnhancer = null;
        }
        effectsSessionId = C.AUDIO_SESSION_ID_UNSET;
    }

    private MediaItem toMediaItem(Song s) {
        MediaMetadata md = new MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setArtworkUri(s.cover != null ? Uri.parse(s.cover) : null)
                .build();

        return new MediaItem.Builder()
                .setMediaId(s.id)
                .setUri(s.path)
                .setMediaMetadata(md)
                .build();
    }

    private void persistCurrentDurationIfKnown(long durationMs) {
        if (durationMs <= 0 || controller == null) return;

        int idx = controller.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= queue.size()) return;

        Song current = queue.get(idx);
        if (current == null || current.id == null || current.id.trim().isEmpty()) return;
        if (current.duration > 0) return;

        current.duration = durationMs;
        Song snapshot = current;
        executor.execute(() -> songDao.updateDuration(snapshot.id, durationMs));
    }

    public void release() {
        releaseEffects();
        if (controller != null) {
            controller.release();
            controller = null;
        }
    }
}

