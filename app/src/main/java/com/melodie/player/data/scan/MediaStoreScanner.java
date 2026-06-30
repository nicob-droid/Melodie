package com.melodie.player.data.scan;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.Song;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Scans MediaStore for local audio tracks.
 */
public class MediaStoreScanner {

    public static class SourceRoot {
        public final long folderSourceId;
        public final String absolutePathPrefix;

        public SourceRoot(long folderSourceId, String absolutePathPrefix) {
            this.folderSourceId = folderSourceId;
            this.absolutePathPrefix = absolutePathPrefix;
        }
    }

    public static class ScanResult {
        public final List<Song> songs;
        public final List<Album> albums;

        public ScanResult(List<Song> songs, List<Album> albums) {
            this.songs = songs;
            this.albums = albums;
        }
    }

    public static ScanResult scan(Context context, List<SourceRoot> sourceRoots) {
        List<Song> songs = new ArrayList<>();
        Map<String, Album> albumMap = new HashMap<>();
        List<SourceRoot> normalizedRoots = normalizeRoots(sourceRoots);

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Uri albumArtBase = Uri.parse("content://media/external/audio/albumart");

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.IS_MUSIC
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
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
            int trackIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
            int durationIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            int dataIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                long albumId = c.getLong(albumIdIdx);
                Uri trackUri = ContentUris.withAppendedId(uri, id);
                Uri coverUri = ContentUris.withAppendedId(albumArtBase, albumId);
                String absolutePath = c.getString(dataIdx);

                long matchedFolderSourceId = resolveFolderSourceId(absolutePath, normalizedRoots);
                if (matchedFolderSourceId < 0L) {
                    continue;
                }
                // Exclut WhatsApp du scan local principal.
                if (isWhatsAppPath(absolutePath)) {
                    continue;
                }

                String albumName = c.getString(albumIdx);
                String artistName = c.getString(artistIdx);
                String releaseYear = toReleaseYear(c.getInt(yearIdx));
                if (releaseYear == null) {
                    releaseYear = extractReleaseYearFromMetadata(context, trackUri, absolutePath);
                }
                String albumKey = buildAlbumKey(artistName, albumName);
                long logicalAlbumId = toLogicalAlbumId(albumKey);

                Song s = new Song();
                s.id = "L_" + id;
                s.title = c.getString(titleIdx);
                s.artist = artistName;
                s.album = albumName;
                // Utilise un id logique stable pour regrouper les variantes MediaStore d'un meme album.
                s.albumId = logicalAlbumId;
                s.releaseDate = releaseYear;
                // TRACK peut encoder disque+piste sous la forme 1XYZ (disque 1, piste XYZ).
                // On conserve la valeur brute pour respecter l'ordre intra-disque.
                int rawTrack = c.getInt(trackIdx);
                s.trackNumber = rawTrack > 0 ? rawTrack : 0;
                s.duration = c.getLong(durationIdx);
                s.path = trackUri.toString();
                s.source = Song.SOURCE_LOCAL;
                s.folderSourceId = matchedFolderSourceId;
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

    private static List<SourceRoot> normalizeRoots(List<SourceRoot> roots) {
        List<SourceRoot> result = new ArrayList<>();
        if (roots == null) return result;
        for (SourceRoot root : roots) {
            if (root == null || root.absolutePathPrefix == null) continue;
            String normalized = normalizePath(root.absolutePathPrefix);
            if (normalized.isEmpty()) continue;
            result.add(new SourceRoot(root.folderSourceId, normalized));
        }
        return result;
    }

    private static long resolveFolderSourceId(String absolutePath, List<SourceRoot> roots) {
        if (roots == null || roots.isEmpty()) return -1L;
        String normalizedPath = normalizePath(absolutePath);
        if (normalizedPath.isEmpty()) return -1L;

        long bestMatchId = -1L;
        int bestLength = -1;
        for (SourceRoot root : roots) {
            String prefix = root.absolutePathPrefix;
            if (prefix.isEmpty()) continue;
            if (normalizedPath.startsWith(prefix) && prefix.length() > bestLength) {
                bestLength = prefix.length();
                bestMatchId = root.folderSourceId;
            }
        }
        return bestMatchId;
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null) return "";
        String path = rawPath.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static boolean isWhatsAppPath(String absolutePath) {
        return normalizePath(absolutePath).contains("whatsapp");
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

    private static String extractReleaseYearFromMetadata(Context context, Uri trackUri, String absolutePath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (trackUri != null) {
                retriever.setDataSource(context, trackUri);
            } else if (absolutePath != null && !absolutePath.trim().isEmpty()) {
                retriever.setDataSource(absolutePath);
            } else {
                return null;
            }

            String year = sanitizeYear(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR));
            if (year != null) return year;

            return sanitizeYear(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // No-op: certains extracteurs jettent une exception à release().
            }
        }
    }

    private static String sanitizeYear(String rawValue) {
        if (rawValue == null) return null;
        String trimmed = rawValue.trim();
        if (trimmed.length() < 4) return null;
        for (int i = 0; i <= trimmed.length() - 4; i++) {
            char c0 = trimmed.charAt(i);
            char c1 = trimmed.charAt(i + 1);
            char c2 = trimmed.charAt(i + 2);
            char c3 = trimmed.charAt(i + 3);
            if (Character.isDigit(c0)
                    && Character.isDigit(c1)
                    && Character.isDigit(c2)
                    && Character.isDigit(c3)) {
                int year = Integer.parseInt(trimmed.substring(i, i + 4));
                if (year >= 1000 && year <= 2999) {
                    return String.valueOf(year);
                }
            }
        }
        return null;
    }
}

