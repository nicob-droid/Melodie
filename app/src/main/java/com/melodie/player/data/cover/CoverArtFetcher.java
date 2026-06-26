package com.melodie.player.data.cover;

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import com.melodie.player.BuildConfig;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.text.Normalizer;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CoverArtFetcher {

    private static final String TAG = "CoverArtFetcher";
    private static final String DISCOGS_USER_AGENT = "MelodiePlayer/1.0";

    @Inject
    public CoverArtFetcher() {
    }

    public String fetchAlbumCover(String artist, String album) {
        String safeArtist = artist != null ? artist.trim() : "";
        String safeAlbum = album != null ? album.trim() : "";
        if (safeArtist.isEmpty() && safeAlbum.isEmpty()) {
            return null;
        }

        Log.d(TAG, "Lookup start artist='" + safeArtist + "' album='" + safeAlbum + "'");
        String discogs = fetchFromDiscogs(safeArtist, safeAlbum);
        if (discogs != null) {
            Log.d(TAG, "Lookup success provider=discogs url=" + discogs);
            return discogs;
        }

        String deezer = fetchFromDeezer(safeArtist, safeAlbum);
        if (deezer != null) {
            Log.d(TAG, "Lookup success provider=deezer url=" + deezer);
            return deezer;
        }
        Log.d(TAG, "Lookup miss provider=deezer");

        String itunesArtistFirst = fetchFromItunesByArtist(safeArtist, safeAlbum);
        if (itunesArtistFirst != null) {
            Log.d(TAG, "Lookup success provider=itunes_artist url=" + itunesArtistFirst);
            return itunesArtistFirst;
        }
        Log.d(TAG, "Lookup miss provider=itunes_artist");

        String itunesGeneric = fetchFromItunesGeneric(safeArtist, safeAlbum);
        if (itunesGeneric != null) {
            Log.d(TAG, "Lookup success provider=itunes_generic url=" + itunesGeneric);
            return itunesGeneric;
        }
        Log.d(TAG, "Lookup miss provider=itunes_generic");

        return null;
    }

    public List<Long> fetchDiscogsTrackDurations(String artist, String album) {
        List<DiscogsTrackInfo> trackInfos = fetchDiscogsTrackInfos(artist, album);
        if (trackInfos == null || trackInfos.isEmpty()) return null;
        List<Long> durations = new ArrayList<>();
        for (DiscogsTrackInfo info : trackInfos) {
            durations.add(info != null ? info.durationMs : 0L);
        }
        return durations;
    }

    public List<DiscogsTrackInfo> fetchDiscogsTrackInfos(String artist, String album) {
        String term = buildSearchTerm(artist, album);
        if (term.isEmpty()) return null;

        String endpoint = "https://api.discogs.com/database/search?type=release&per_page=50&q="
                + Uri.encode(term);
        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;
        Log.d(TAG, "Discogs duration request term='" + term + "' token=" + (token.isEmpty() ? "none" : "configured"));

        try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            Long releaseId = parseBestDiscogsReleaseId(reader, artist, album);
            if (releaseId == null) {
                return null;
            }
            return fetchDiscogsTrackInfosForRelease(releaseId.longValue());
        } catch (Exception e) {
            Log.d(TAG, "Discogs duration lookup failed for " + term, e);
            return null;
        }
    }

    private String fetchFromDeezer(String artist, String album) {
        String query = buildDeezerQuery(artist, album);
        if (query.isEmpty()) return null;

        String endpoint = "https://api.deezer.com/search/album?q=" + Uri.encode(query);
        try (InputStream input = openJsonStream(endpoint);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseBestArtworkFromDeezer(reader, artist, album);
        } catch (Exception e) {
            Log.d(TAG, "Deezer cover lookup failed for " + query, e);
            return null;
        }
    }

    private String fetchFromItunesByArtist(String artist, String album) {
        if (artist.isEmpty()) return null;
        String endpoint = "https://itunes.apple.com/search?media=music&entity=album&attribute=artistTerm&limit=100&term="
                + Uri.encode(artist);
        try (InputStream input = openJsonStream(endpoint);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseBestArtworkFromItunes(reader, artist, album, 60);
        } catch (Exception e) {
            Log.d(TAG, "iTunes artist cover lookup failed for " + artist, e);
            return null;
        }
    }

    private String fetchFromItunesGeneric(String artist, String album) {
        String term = buildSearchTerm(artist, album);
        if (term.isEmpty()) return null;

        String endpoint = "https://itunes.apple.com/search?media=music&entity=album&limit=50&term=" + Uri.encode(term);
        try (InputStream input = openJsonStream(endpoint);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseBestArtworkFromItunes(reader, artist, album, 95);
        } catch (Exception e) {
            Log.d(TAG, "iTunes generic cover lookup failed for " + term, e);
            return null;
        }
    }

    private String fetchFromDiscogs(String artist, String album) {
        String term = buildSearchTerm(artist, album);
        if (term.isEmpty()) return null;

        String endpoint = "https://api.discogs.com/database/search?type=release&per_page=50&q="
                + Uri.encode(term);
        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;
        Log.d(TAG, "Discogs request term='" + term + "' token=" + (token.isEmpty() ? "none" : "configured"));

        try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String cover = parseBestArtworkFromDiscogs(reader, artist, album);
            if (cover == null) {
                Log.d(TAG, "Discogs parsed but no matching cover for term='" + term + "'");
            }
            return cover;
        } catch (Exception e) {
            Log.d(TAG, "Discogs cover lookup failed for " + term, e);
            return null;
        }
    }

    private InputStream openJsonStream(String endpoint) throws Exception {
        return openJsonStream(endpoint, null, null);
    }

    private InputStream openJsonStream(String endpoint, String userAgent, String authorization) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            if (userAgent != null && !userAgent.trim().isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            if (authorization != null && !authorization.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", authorization);
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }

            InputStream stream = connection.getInputStream();
            HttpURLConnection finalConnection = connection;
            return new java.io.FilterInputStream(stream) {
                @Override
                public void close() throws java.io.IOException {
                    super.close();
                    finalConnection.disconnect();
                }
            };
        } catch (Exception e) {
            if (connection != null) {
                connection.disconnect();
            }
            throw e;
        }
    }

    private String parseBestArtworkFromDiscogs(JsonReader reader, String expectedArtist,
                                               String expectedAlbum) throws Exception {
        String wantedArtist = normalize(expectedArtist);
        String wantedAlbum = normalize(expectedAlbum);
        String bestArtwork = null;
        int bestScore = Integer.MIN_VALUE;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("results".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String title = "";
                    String coverImage = null;

                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("title".equals(child) && reader.peek() != JsonToken.NULL) {
                            title = reader.nextString();
                        } else if ("cover_image".equals(child) && reader.peek() != JsonToken.NULL) {
                            coverImage = reader.nextString();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();

                    String candidateArtist = "";
                    String candidateAlbum = title;
                    int separator = title.indexOf(" - ");
                    if (separator > 0) {
                        candidateArtist = title.substring(0, separator);
                        candidateAlbum = title.substring(separator + 3);
                    }

                    int score = scoreCandidate(wantedArtist, wantedAlbum,
                            normalize(candidateArtist), normalize(candidateAlbum));
                    if (coverImage != null && !coverImage.trim().isEmpty() && score > bestScore) {
                        bestScore = score;
                        bestArtwork = coverImage;
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (bestArtwork == null || bestArtwork.trim().isEmpty() || bestScore < 90) {
            Log.d(TAG, "Discogs best score below threshold score=" + bestScore + " threshold=90");
            return null;
        }
        Log.d(TAG, "Discogs best score=" + bestScore + " selected=" + bestArtwork);
        return bestArtwork;
    }

    private Long parseBestDiscogsReleaseId(JsonReader reader, String expectedArtist, String expectedAlbum) throws Exception {
        String wantedArtist = normalize(expectedArtist);
        String wantedAlbum = normalize(expectedAlbum);
        Long bestReleaseId = null;
        int bestScore = Integer.MIN_VALUE;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("results".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    long releaseId = -1L;
                    String title = "";

                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("id".equals(child) && reader.peek() != JsonToken.NULL) {
                            releaseId = reader.nextLong();
                        } else if ("title".equals(child) && reader.peek() != JsonToken.NULL) {
                            title = reader.nextString();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();

                    String candidateArtist = "";
                    String candidateAlbum = title;
                    int separator = title.indexOf(" - ");
                    if (separator > 0) {
                        candidateArtist = title.substring(0, separator);
                        candidateAlbum = title.substring(separator + 3);
                    }

                    int score = scoreCandidate(wantedArtist, wantedAlbum,
                            normalize(candidateArtist), normalize(candidateAlbum));
                    if (releaseId > 0L && score > bestScore) {
                        bestScore = score;
                        bestReleaseId = releaseId;
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (bestReleaseId == null || bestScore < 90) {
            Log.d(TAG, "Discogs duration best score below threshold score=" + bestScore + " threshold=90");
            return null;
        }
        return bestReleaseId;
    }

    private List<DiscogsTrackInfo> fetchDiscogsTrackInfosForRelease(long releaseId) {
        String endpoint = "https://api.discogs.com/releases/" + releaseId;
        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;

        try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseTrackInfosFromDiscogsRelease(reader);
        } catch (Exception e) {
            Log.d(TAG, "Discogs release duration lookup failed for id=" + releaseId, e);
            return null;
        }
    }

    private List<DiscogsTrackInfo> parseTrackInfosFromDiscogsRelease(JsonReader reader) throws Exception {
        List<DiscogsTrackInfo> tracks = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("tracklist".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String title = null;
                    String durationText = null;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("title".equals(child) && reader.peek() != JsonToken.NULL) {
                            title = reader.nextString();
                        } else if ("duration".equals(child) && reader.peek() != JsonToken.NULL) {
                            durationText = reader.nextString();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                    tracks.add(new DiscogsTrackInfo(title, parseDurationMs(durationText)));
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return tracks;
    }

    private long parseDurationMs(String text) {
        if (text == null) return 0L;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0L;

        try {
            String[] parts = trimmed.split(":");
            long totalSeconds = 0L;
            if (parts.length == 2) {
                totalSeconds = Integer.parseInt(parts[0].trim()) * 60L + Integer.parseInt(parts[1].trim());
            } else if (parts.length == 3) {
                totalSeconds = Integer.parseInt(parts[0].trim()) * 3600L
                        + Integer.parseInt(parts[1].trim()) * 60L
                        + Integer.parseInt(parts[2].trim());
            } else {
                return 0L;
            }
            return totalSeconds * 1000L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String buildSearchTerm(String artist, String album) {
        String safeArtist = artist != null ? artist.trim() : "";
        String safeAlbum = album != null ? album.trim() : "";
        if (safeArtist.isEmpty() && safeAlbum.isEmpty()) {
            return "";
        }
        if (safeArtist.isEmpty()) {
            return safeAlbum;
        }
        if (safeAlbum.isEmpty()) {
            return safeArtist;
        }
        return safeArtist + " " + safeAlbum;
    }

    private String parseBestArtworkFromItunes(JsonReader reader, String expectedArtist,
                                              String expectedAlbum, int minScore) throws Exception {
        String wantedArtist = normalize(expectedArtist);
        String wantedAlbum = normalize(expectedAlbum);
        String bestArtwork = null;
        int bestScore = Integer.MIN_VALUE;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("results".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String artistName = "";
                    String collectionName = "";
                    String artworkUrl100 = null;

                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("artistName".equals(child) && reader.peek() != JsonToken.NULL) {
                            artistName = reader.nextString();
                        } else if ("collectionName".equals(child) && reader.peek() != JsonToken.NULL) {
                            collectionName = reader.nextString();
                        } else if ("artworkUrl100".equals(child) && reader.peek() != JsonToken.NULL) {
                            artworkUrl100 = reader.nextString();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();

                    int score = scoreCandidate(wantedArtist, wantedAlbum,
                            normalize(artistName), normalize(collectionName));
                    if (artworkUrl100 != null && score > bestScore) {
                        bestScore = score;
                        bestArtwork = artworkUrl100;
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (bestArtwork == null || bestArtwork.trim().isEmpty() || bestScore < minScore) {
            Log.d(TAG, "iTunes best score below threshold score=" + bestScore + " threshold=" + minScore);
            return null;
        }
        Log.d(TAG, "iTunes best score=" + bestScore + " threshold=" + minScore);
        return bestArtwork.replace("100x100bb", "600x600bb");
    }

    private String parseBestArtworkFromDeezer(JsonReader reader, String expectedArtist,
                                              String expectedAlbum) throws Exception {
        String wantedArtist = normalize(expectedArtist);
        String wantedAlbum = normalize(expectedAlbum);
        String bestArtwork = null;
        int bestScore = Integer.MIN_VALUE;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("data".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String artistName = "";
                    String albumName = "";
                    String artwork = null;

                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("title".equals(child) && reader.peek() != JsonToken.NULL) {
                            albumName = reader.nextString();
                        } else if ("cover_xl".equals(child) && reader.peek() != JsonToken.NULL) {
                            artwork = reader.nextString();
                        } else if ("cover_big".equals(child) && reader.peek() != JsonToken.NULL && artwork == null) {
                            artwork = reader.nextString();
                        } else if ("artist".equals(child) && reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String artistField = reader.nextName();
                                if ("name".equals(artistField) && reader.peek() != JsonToken.NULL) {
                                    artistName = reader.nextString();
                                } else {
                                    reader.skipValue();
                                }
                            }
                            reader.endObject();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();

                    int score = scoreCandidate(wantedArtist, wantedAlbum,
                            normalize(artistName), normalize(albumName));
                    if (artwork != null && score > bestScore) {
                        bestScore = score;
                        bestArtwork = artwork;
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        // Deezer est deja cible par artiste+album, seuil modere.
        if (bestArtwork == null || bestArtwork.trim().isEmpty() || bestScore < 70) {
            Log.d(TAG, "Deezer best score below threshold score=" + bestScore + " threshold=70");
            return null;
        }
        Log.d(TAG, "Deezer best score=" + bestScore + " threshold=70");
        return bestArtwork;
    }

    private int scoreCandidate(String wantedArtist, String wantedAlbum,
                               String candidateArtist, String candidateAlbum) {
        int score = 0;

        if (!wantedArtist.isEmpty() && !candidateArtist.isEmpty()) {
            if (candidateArtist.equals(wantedArtist)) score += 120;
            else if (candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist)) score += 60;
            else score -= 120;
        }

        if (!wantedAlbum.isEmpty() && !candidateAlbum.isEmpty()) {
            if (candidateAlbum.equals(wantedAlbum)) score += 120;
            else if (candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum)) score += 70;
            else score += commonTokenScore(wantedAlbum, candidateAlbum);
        }

        return score;
    }

    private int commonTokenScore(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        String[] left = a.split("\\s+");
        int points = 0;
        for (String token : left) {
            if (token.length() < 3) continue;
            if (b.contains(token)) {
                points += 12;
            }
        }
        return points;
    }

    private String buildDeezerQuery(String artist, String album) {
        String safeArtist = artist != null ? artist.trim() : "";
        String safeAlbum = album != null ? album.trim() : "";
        if (safeArtist.isEmpty() && safeAlbum.isEmpty()) {
            return "";
        }
        if (safeArtist.isEmpty()) {
            return "album:\"" + safeAlbum + "\"";
        }
        if (safeAlbum.isEmpty()) {
            return "artist:\"" + safeArtist + "\"";
        }
        return "artist:\"" + safeArtist + "\" album:\"" + safeAlbum + "\"";
    }

    private String normalize(String text) {
        if (text == null) return "";
        String lower = text.trim().toLowerCase();
        String ascii = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return ascii.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    public static class DiscogsTrackInfo {
        public final String title;
        public final long durationMs;

        public DiscogsTrackInfo(String title, long durationMs) {
            this.title = title;
            this.durationMs = durationMs;
        }
    }
}

