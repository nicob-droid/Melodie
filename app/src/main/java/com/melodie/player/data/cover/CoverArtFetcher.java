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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CoverArtFetcher {

    private static final String TAG = "CoverArtFetcher";
    private static final String DISCOGS_USER_AGENT = "MelodiePlayer/1.0";
    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})");
    private static final int MAX_HTTP_429_RETRIES = 2;
    private static final long DEFAULT_429_RETRY_MS = 1500L;
    private static volatile long discogsRetryNotBeforeMs = 0L;

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

    public String fetchAlbumReleaseDate(String artist, String album) {
        String safeArtist = artist != null ? artist.trim() : "";
        String safeAlbum = album != null ? album.trim() : "";
        if (safeArtist.isEmpty() && safeAlbum.isEmpty()) {
            return null;
        }

        String discogs = fetchReleaseDateFromDiscogs(safeArtist, safeAlbum);
        if (discogs != null && !discogs.trim().isEmpty()) {
            return discogs;
        }

        return fetchReleaseDateFromDeezer(safeArtist, safeAlbum);
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
            Long releaseId = parseBestDiscogsReleaseId(reader, artist, album, 90);
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

    private String fetchReleaseDateFromDiscogs(String artist, String album) {
        String term = buildSearchTerm(artist, album);
        if (term.isEmpty()) return null;

        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;

        String normalizedAlbum = normalizeAlbumForDateLookup(album);
        List<String> dateEndpoints = new ArrayList<>();
        addUniqueEndpoints(dateEndpoints, buildDiscogsSearchEndpoints(artist, normalizedAlbum));
        if (!normalize(normalizedAlbum).equals(normalize(album))) {
            addUniqueEndpoints(dateEndpoints, buildDiscogsSearchEndpoints(artist, album));
        }

        for (String endpoint : dateEndpoints) {
            try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
                 JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                DiscogsMatch match = parseBestDiscogsMatch(reader, artist, normalizedAlbum, 58);
                if (match == null) {
                    continue;
                }

                if (match.masterId > 0L) {
                    String fromMaster = fetchDiscogsYearForMaster(match.masterId);
                    if (fromMaster != null && !fromMaster.trim().isEmpty()) {
                        return fromMaster;
                    }
                }

                if (match.releaseId > 0L) {
                    String fromRelease = fetchDiscogsReleaseDateForRelease(match.releaseId);
                    if (fromRelease != null && !fromRelease.trim().isEmpty()) {
                        return fromRelease;
                    }
                }

                String fromSearchYear = normalizeReleaseDate(match.year);
                if (fromSearchYear != null) return fromSearchYear;
            } catch (Exception e) {
                Log.d(TAG, "Discogs release date lookup failed for endpoint=" + endpoint, e);
            }
        }

        return null;
    }

    private String fetchReleaseDateFromDeezer(String artist, String album) {
        String normalizedAlbum = normalizeAlbumForDateLookup(album);
        String query = buildDeezerQuery(artist, normalizedAlbum);
        if (query.isEmpty()) return null;

        String endpoint = "https://api.deezer.com/search/album?q=" + Uri.encode(query);
        try (InputStream input = openJsonStream(endpoint);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseBestReleaseDateFromDeezer(reader, artist, album);
        } catch (Exception e) {
            Log.d(TAG, "Deezer release date lookup failed for " + query, e);
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

        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;
        for (String endpoint : buildDiscogsSearchEndpoints(artist, album)) {
            Log.d(TAG, "Discogs request endpoint='" + endpoint + "' token=" + (token.isEmpty() ? "none" : "configured"));
            try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
                 JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                DiscogsMatch match = parseBestDiscogsMatch(reader, artist, album, 90);
                if (match == null) {
                    continue;
                }

                if (match.releaseId > 0L) {
                    String detailedCover = fetchDiscogsCoverForRelease(match.releaseId);
                    if (detailedCover != null && !detailedCover.trim().isEmpty()) {
                        return detailedCover;
                    }
                }

                if (match.coverImage != null && !match.coverImage.trim().isEmpty()) {
                    return match.coverImage;
                }
            } catch (Exception e) {
                Log.d(TAG, "Discogs cover lookup failed for endpoint=" + endpoint, e);
            }
        }

        Log.d(TAG, "Discogs parsed but no matching cover for term='" + term + "'");
        return null;
    }

    private List<String> buildDiscogsSearchEndpoints(String artist, String album) {
        List<String> endpoints = new ArrayList<>();
        String safeArtist = artist != null ? artist.trim() : "";
        String safeAlbum = album != null ? album.trim() : "";

        if (!safeArtist.isEmpty() && !safeAlbum.isEmpty()) {
            endpoints.add("https://api.discogs.com/database/search?type=release&per_page=50&artist="
                    + Uri.encode(safeArtist)
                    + "&release_title="
                    + Uri.encode(safeAlbum)
                    + "&format=Album");
        }

        String term = buildSearchTerm(safeArtist, safeAlbum);
        if (!term.isEmpty()) {
            endpoints.add("https://api.discogs.com/database/search?type=release&per_page=50&q=" + Uri.encode(term));
        }

        return endpoints;
    }

    private void addUniqueEndpoints(List<String> target, List<String> candidates) {
        if (target == null || candidates == null) return;
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            if (!target.contains(candidate)) {
                target.add(candidate);
            }
        }
    }

    private String normalizeAlbumForDateLookup(String album) {
        String safeAlbum = album != null ? album.trim() : "";
        if (safeAlbum.isEmpty()) return "";

        String normalized = safeAlbum;
        normalized = normalized.replaceAll("(?i)\\s*[\\[(][^\\])]*(edition|bonus|tracks?|japan|japanese|deluxe|remaster(?:ed)?|anniversary)[^\\])]*[\\])]\\s*$", "");
        normalized = normalized.replaceAll("(?i)\\s*-\\s*(japan(?:ese)?\\s+)?(edition|deluxe(?:\\s+edition)?|bonus\\s+tracks?|remaster(?:ed)?|anniversary\\s+edition)\\s*$", "");
        normalized = normalized.replaceAll("\\s{2,}", " ").trim();

        return normalized.isEmpty() ? safeAlbum : normalized;
    }

    private InputStream openJsonStream(String endpoint) throws Exception {
        return openJsonStream(endpoint, null, null);
    }

    private InputStream openJsonStream(String endpoint, String userAgent, String authorization) throws Exception {
        boolean isDiscogs = endpoint != null && endpoint.contains("api.discogs.com");

        for (int attempt = 0; attempt <= MAX_HTTP_429_RETRIES; attempt++) {
            if (isDiscogs) {
                awaitDiscogsCooldown();
            }

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
                if (code == 429) {
                    long retryDelayMs = resolveRetryDelayMs(connection, attempt);
                    connection.disconnect();
                    if (isDiscogs) {
                        registerDiscogsCooldown(retryDelayMs);
                    }
                    if (attempt < MAX_HTTP_429_RETRIES) {
                        Log.d(TAG, "HTTP 429 for endpoint=" + endpoint + ", retry in " + retryDelayMs + "ms");
                        sleepQuietly(retryDelayMs);
                        continue;
                    }
                    throw new IllegalStateException("HTTP 429");
                }

                if (code < 200 || code >= 300) {
                    connection.disconnect();
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

        throw new IllegalStateException("HTTP 429");
    }

    private long resolveRetryDelayMs(HttpURLConnection connection, int attempt) {
        String retryAfter = connection != null ? connection.getHeaderField("Retry-After") : null;
        long parsedMs = parseRetryAfterMs(retryAfter);
        if (parsedMs > 0L) return parsedMs;
        return fallbackRetryDelayMs(attempt);
    }

    private long parseRetryAfterMs(String retryAfter) {
        if (retryAfter == null) return -1L;
        String trimmed = retryAfter.trim();
        if (trimmed.isEmpty()) return -1L;
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds <= 0L) return -1L;
            return Math.min(30000L, seconds * 1000L);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private long fallbackRetryDelayMs(int attempt) {
        long delay = DEFAULT_429_RETRY_MS * (1L << Math.max(0, attempt));
        return Math.min(10000L, delay);
    }

    private void registerDiscogsCooldown(long delayMs) {
        long safeDelay = Math.max(500L, delayMs);
        long nextAllowed = System.currentTimeMillis() + safeDelay;
        synchronized (CoverArtFetcher.class) {
            if (nextAllowed > discogsRetryNotBeforeMs) {
                discogsRetryNotBeforeMs = nextAllowed;
            }
        }
    }

    private void awaitDiscogsCooldown() {
        long waitMs = discogsRetryNotBeforeMs - System.currentTimeMillis();
        if (waitMs > 0L) {
            sleepQuietly(waitMs);
        }
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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

    private String parseBestReleaseDateFromDiscogs(JsonReader reader, String expectedArtist,
                                                   String expectedAlbum) throws Exception {
        String wantedArtist = normalize(expectedArtist);
        String wantedAlbum = normalize(expectedAlbum);
        String bestReleaseDate = null;
        int bestScore = Integer.MIN_VALUE;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("results".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String title = "";
                    String yearText = null;

                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("title".equals(child) && reader.peek() != JsonToken.NULL) {
                            title = reader.nextString();
                        } else if ("year".equals(child) && reader.peek() != JsonToken.NULL) {
                            if (reader.peek() == JsonToken.NUMBER) {
                                yearText = String.valueOf(reader.nextInt());
                            } else if (reader.peek() == JsonToken.STRING) {
                                yearText = reader.nextString();
                            } else {
                                reader.skipValue();
                            }
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

                    String normalizedYear = normalizeReleaseDate(yearText);
                    int score = scoreCandidate(wantedArtist, wantedAlbum,
                            normalize(candidateArtist), normalize(candidateAlbum));
                    if (normalizedYear != null && score > bestScore) {
                        bestScore = score;
                        bestReleaseDate = normalizedYear;
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (bestReleaseDate == null || bestScore < 70) {
            return null;
        }
        return bestReleaseDate;
    }

    private Long parseBestDiscogsReleaseId(JsonReader reader, String expectedArtist, String expectedAlbum) throws Exception {
        return parseBestDiscogsReleaseId(reader, expectedArtist, expectedAlbum, 90);
    }

    private Long parseBestDiscogsReleaseId(JsonReader reader, String expectedArtist, String expectedAlbum, int minScore) throws Exception {
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

        if (bestReleaseId == null || bestScore < minScore) {
            Log.d(TAG, "Discogs release-id best score below threshold score=" + bestScore + " threshold=" + minScore);
            return null;
        }
        return bestReleaseId;
    }

    private DiscogsMatch parseBestDiscogsMatch(JsonReader reader, String expectedArtist,
                                              String expectedAlbum, int minScore) throws Exception {
        String wantedArtist = normalize(expectedArtist);
        String wantedAlbum = normalize(expectedAlbum);
        DiscogsMatch best = null;
        int bestScore = Integer.MIN_VALUE;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!"results".equals(name)) {
                reader.skipValue();
                continue;
            }

            reader.beginArray();
            while (reader.hasNext()) {
                long releaseId = -1L;
                long masterId = -1L;
                String title = "";
                String year = null;
                String coverImage = null;

                reader.beginObject();
                while (reader.hasNext()) {
                    String child = reader.nextName();
                    if ("id".equals(child) && reader.peek() != JsonToken.NULL) {
                        releaseId = reader.nextLong();
                    } else if ("master_id".equals(child) && reader.peek() != JsonToken.NULL) {
                        if (reader.peek() == JsonToken.NUMBER) {
                            masterId = reader.nextLong();
                        } else if (reader.peek() == JsonToken.STRING) {
                            try {
                                masterId = Long.parseLong(reader.nextString());
                            } catch (Exception ignored) {
                                masterId = -1L;
                            }
                        } else {
                            reader.skipValue();
                        }
                    } else if ("title".equals(child) && reader.peek() != JsonToken.NULL) {
                        title = reader.nextString();
                    } else if ("year".equals(child) && reader.peek() != JsonToken.NULL) {
                        if (reader.peek() == JsonToken.NUMBER) {
                            year = String.valueOf(reader.nextInt());
                        } else if (reader.peek() == JsonToken.STRING) {
                            year = reader.nextString();
                        } else {
                            reader.skipValue();
                        }
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
                if (releaseId > 0L && score > bestScore) {
                    bestScore = score;
                    best = new DiscogsMatch(releaseId, masterId, year, coverImage);
                }
            }
            reader.endArray();
        }
        reader.endObject();

        if (best == null || bestScore < minScore) {
            Log.d(TAG, "Discogs best match below threshold score=" + bestScore + " threshold=" + minScore);
            return null;
        }
        return best;
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

    private String fetchDiscogsReleaseDateForRelease(long releaseId) {
        String endpoint = "https://api.discogs.com/releases/" + releaseId;
        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;

        try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseReleaseDateFromDiscogsRelease(reader);
        } catch (Exception e) {
            Log.d(TAG, "Discogs release date detail lookup failed for id=" + releaseId, e);
            return null;
        }
    }

    private String fetchDiscogsCoverForRelease(long releaseId) {
        String endpoint = "https://api.discogs.com/releases/" + releaseId;
        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;

        try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseCoverFromDiscogsRelease(reader);
        } catch (Exception e) {
            Log.d(TAG, "Discogs cover detail lookup failed for id=" + releaseId, e);
            return null;
        }
    }

    private String fetchDiscogsYearForMaster(long masterId) {
        String endpoint = "https://api.discogs.com/masters/" + masterId;
        String token = BuildConfig.DISCOGS_TOKEN != null ? BuildConfig.DISCOGS_TOKEN.trim() : "";
        String authorization = token.isEmpty() ? null : "Discogs token=" + token;

        try (InputStream input = openJsonStream(endpoint, DISCOGS_USER_AGENT, authorization);
             JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return parseYearFromDiscogsMaster(reader);
        } catch (Exception e) {
            Log.d(TAG, "Discogs master year lookup failed for id=" + masterId, e);
            return null;
        }
    }

    private String parseCoverFromDiscogsRelease(JsonReader reader) throws Exception {
        String primary = null;
        String first = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!"images".equals(name) || reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue();
                continue;
            }

            reader.beginArray();
            while (reader.hasNext()) {
                String type = "";
                String uri = null;
                String uri150 = null;

                reader.beginObject();
                while (reader.hasNext()) {
                    String child = reader.nextName();
                    if ("type".equals(child) && reader.peek() != JsonToken.NULL) {
                        type = reader.nextString();
                    } else if ("uri".equals(child) && reader.peek() != JsonToken.NULL) {
                        uri = reader.nextString();
                    } else if ("uri150".equals(child) && reader.peek() != JsonToken.NULL) {
                        uri150 = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();

                String chosen = (uri != null && !uri.trim().isEmpty()) ? uri : uri150;
                if (chosen != null && !chosen.trim().isEmpty()) {
                    if (first == null) first = chosen;
                    if ("primary".equalsIgnoreCase(type)) {
                        primary = chosen;
                    }
                }
            }
            reader.endArray();
        }
        reader.endObject();

        if (primary != null && !primary.trim().isEmpty()) return primary;
        return first;
    }

    private String parseYearFromDiscogsMaster(JsonReader reader) throws Exception {
        String year = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("year".equals(name) && reader.peek() != JsonToken.NULL) {
                if (reader.peek() == JsonToken.NUMBER) {
                    year = String.valueOf(reader.nextInt());
                } else if (reader.peek() == JsonToken.STRING) {
                    year = reader.nextString();
                } else {
                    reader.skipValue();
                }
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return normalizeReleaseDate(year);
    }

    private String parseReleaseDateFromDiscogsRelease(JsonReader reader) throws Exception {
        String year = null;
        String released = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("year".equals(name) && reader.peek() != JsonToken.NULL) {
                if (reader.peek() == JsonToken.NUMBER) {
                    year = String.valueOf(reader.nextInt());
                } else if (reader.peek() == JsonToken.STRING) {
                    year = reader.nextString();
                } else {
                    reader.skipValue();
                }
            } else if ("released".equals(name) && reader.peek() != JsonToken.NULL) {
                if (reader.peek() == JsonToken.STRING) {
                    released = reader.nextString();
                } else {
                    reader.skipValue();
                }
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        String normalizedYear = normalizeReleaseDate(year);
        if (normalizedYear != null) return normalizedYear;
        return normalizeReleaseDate(released);
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

    private String parseBestReleaseDateFromDeezer(JsonReader reader, String expectedArtist,
                                                  String expectedAlbum) throws Exception {
        String wantedArtist = normalize(expectedArtist);
        String wantedAlbum = normalize(expectedAlbum);
        String bestReleaseDate = null;
        int bestScore = Integer.MIN_VALUE;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("data".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String artistName = "";
                    String albumName = "";
                    String releaseDate = null;

                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("title".equals(child) && reader.peek() != JsonToken.NULL) {
                            albumName = reader.nextString();
                        } else if ("release_date".equals(child) && reader.peek() != JsonToken.NULL) {
                            releaseDate = reader.nextString();
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

                    String normalizedDate = normalizeReleaseDate(releaseDate);
                    int score = scoreCandidate(wantedArtist, wantedAlbum,
                            normalize(artistName), normalize(albumName));
                    if (normalizedDate != null && score > bestScore) {
                        bestScore = score;
                        bestReleaseDate = normalizedDate;
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (bestReleaseDate == null || bestScore < 70) {
            return null;
        }
        return bestReleaseDate;
    }

    private int scoreCandidate(String wantedArtist, String wantedAlbum,
                               String candidateArtist, String candidateAlbum) {
        int score = 0;

        if (!wantedArtist.isEmpty()) {
            if (candidateArtist.isEmpty()) score -= 90;
            else if (candidateArtist.equals(wantedArtist)) score += 130;
            else if (candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist)) score += 60;
            else score -= 130;
        }

        if (!wantedAlbum.isEmpty()) {
            if (candidateAlbum.isEmpty()) {
                score -= 120;
            } else if (candidateAlbum.equals(wantedAlbum)) {
                score += 140;
            } else if (candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum)) {
                score += 70;
            } else {
                int tokenScore = commonTokenScore(wantedAlbum, candidateAlbum);
                if (tokenScore == 0) score -= 70;
                score += tokenScore;
            }
        }

        score += editionNoisePenalty(wantedAlbum, candidateAlbum);

        return score;
    }

    private int editionNoisePenalty(String wantedAlbum, String candidateAlbum) {
        if (wantedAlbum == null || wantedAlbum.isEmpty() || candidateAlbum == null || candidateAlbum.isEmpty()) {
            return 0;
        }

        int penalty = 0;
        penalty += tokenNoisePenalty(wantedAlbum, candidateAlbum, "deluxe", 30);
        penalty += tokenNoisePenalty(wantedAlbum, candidateAlbum, "remaster", 25);
        penalty += tokenNoisePenalty(wantedAlbum, candidateAlbum, "remastered", 25);
        penalty += tokenNoisePenalty(wantedAlbum, candidateAlbum, "live", 35);
        penalty += tokenNoisePenalty(wantedAlbum, candidateAlbum, "anniversary", 20);
        penalty += tokenNoisePenalty(wantedAlbum, candidateAlbum, "edition", 20);
        return -penalty;
    }

    private int tokenNoisePenalty(String wantedAlbum, String candidateAlbum, String token, int weight) {
        boolean wantedHas = wantedAlbum.contains(token);
        boolean candidateHas = candidateAlbum.contains(token);
        return (!wantedHas && candidateHas) ? weight : 0;
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

    private String normalizeReleaseDate(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        Matcher matcher = YEAR_PATTERN.matcher(trimmed);
        if (!matcher.find()) return null;
        return matcher.group(1);
    }

    private static class DiscogsMatch {
        final long releaseId;
        final long masterId;
        final String year;
        final String coverImage;

        DiscogsMatch(long releaseId, long masterId, String year, String coverImage) {
            this.releaseId = releaseId;
            this.masterId = masterId;
            this.year = year;
            this.coverImage = coverImage;
        }
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

