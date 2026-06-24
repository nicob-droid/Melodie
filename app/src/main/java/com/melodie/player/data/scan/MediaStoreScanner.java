package com.melodie.player.data.scan;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.Song;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans MediaStore for local audio tracks.
 */
public class MediaStoreScanner {

    public static class ScanResult {
        public final List<Song> songs;
        public final List<Album> albums;

        public ScanResult(List<Song> songs, List<Album> albums) {
            this.songs = songs;
            this.albums = albums;
        }
    }

    public static ScanResult scan(Context context) {
        List<Song> songs = new ArrayList<>();
        Map<String, Album> albumMap = new HashMap<>();

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Uri albumArtBase = Uri.parse("content://media/external/audio/albumart");

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.IS_MUSIC
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                + MediaStore.Audio.Media.DATA + " NOT LIKE '%WhatsApp%' AND "
                + MediaStore.Audio.Media.DATA + " NOT LIKE '%Telegram%' AND "
                + MediaStore.Audio.Media.DATA + " NOT LIKE '%Cache%'";

        try (Cursor c = context.getContentResolver().query(uri, projection, selection, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c == null) return new ScanResult(songs, new ArrayList<>());

            int idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int albumIdIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int yearIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR);
            int durationIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                long albumId = c.getLong(albumIdIdx);
                Uri trackUri = ContentUris.withAppendedId(uri, id);
                Uri coverUri = ContentUris.withAppendedId(albumArtBase, albumId);

                String albumName = c.getString(albumIdx);
                String artistName = c.getString(artistIdx);
                String releaseYear = toReleaseYear(c.getInt(yearIdx));
                String albumKey = buildAlbumKey(artistName, albumName);
                long logicalAlbumId = toLogicalAlbumId(albumKey);

                Song s = new Song();
                s.id = "L_" + id;
                s.title = c.getString(titleIdx);
                s.artist = artistName;
                s.album = albumName;
                // Utilise un id logique stable pour regrouper les variantes MediaStore d'un meme album.
                s.albumId = logicalAlbumId;
                s.duration = c.getLong(durationIdx);
                s.path = trackUri.toString();
                s.source = Song.SOURCE_LOCAL;
                s.cover = coverUri.toString();
                s.favorite = false;
                s.dateAdded = c.getLong(dateIdx) * 1000L;
                songs.add(s);

                Album a = albumMap.get(albumKey);
                if (a == null) {
                    a = new Album();
                    a.id = logicalAlbumId;
                    a.name = s.album != null ? s.album : "Unknown";
                    a.artist = s.artist;
                    a.cover = coverUri.toString();
                    a.releaseDate = releaseYear;
                    a.count = 0;
                    albumMap.put(albumKey, a);
                } else if ((a.releaseDate == null || a.releaseDate.isEmpty()) && releaseYear != null) {
                    a.releaseDate = releaseYear;
                }
                a.count++;
            }
        }

        return new ScanResult(songs, new ArrayList<>(albumMap.values()));
    }

    private static String buildAlbumKey(String artist, String album) {
        String safeArtist = normalize(artist);
        String safeAlbum = normalize(album);
        return safeArtist + "||" + safeAlbum;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase();
    }

    private static long toLogicalAlbumId(String key) {
        long hash = 1469598103934665603L; // FNV-1a 64-bit offset basis
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 1099511628211L; // FNV-1a 64-bit prime
        }
        if (hash == Long.MIN_VALUE) return 0L;
        return Math.abs(hash);
    }

    private static String toReleaseYear(int year) {
        if (year <= 0) return null;
        return String.valueOf(year);
    }
}

