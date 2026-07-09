package com.melodie.player.data.repository;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DriveRepositoryAlbumNormalizationTest {

    @Test
    public void stripArtistPrefixWhenMatching_stripsWhenArtistMatches() {
        String album = DriveRepository.stripArtistPrefixWhenMatching("Blur - The Best Of", "Blur");
        assertEquals("The Best Of", album);
    }

    @Test
    public void stripArtistPrefixWhenMatching_keepsWhenArtistDiffers() {
        String album = DriveRepository.stripArtistPrefixWhenMatching("Blur - The Best Of", "Oasis");
        assertEquals("Blur - The Best Of", album);
    }

    @Test
    public void stripArtistPrefixWhenMatching_keepsWhenArtistMissing() {
        String album = DriveRepository.stripArtistPrefixWhenMatching("Blur - The Best Of", null);
        assertEquals("Blur - The Best Of", album);
    }

    @Test
    public void stripArtistPrefixWhenMatching_ignoresCase() {
        String album = DriveRepository.stripArtistPrefixWhenMatching("BLUR - The Best Of", "blur");
        assertEquals("The Best Of", album);
    }

    @Test
    public void stripArtistPrefixWhenMatching_stripsThenYearPrefixInRemainingAlbum() {
        String album = DriveRepository.stripYearNoise(
                DriveRepository.stripArtistPrefixWhenMatching("Blur - [2000] The Best Of", "Blur"));
        assertEquals("The Best Of", album);
    }

    @Test
    public void stripYearNoise_stripsTrailingYear() {
        String album = DriveRepository.stripYearNoise("The Best Of (2000)");
        assertEquals("The Best Of", album);
    }

    @Test
    public void stripArtistPrefixWhenMatching_supportsDashWithoutSpaces_thenYearCleanup() {
        String album = DriveRepository.stripYearNoise(
                DriveRepository.stripArtistPrefixWhenMatching("Blur-[2000] The Best Of", "Blur"));
        assertEquals("The Best Of", album);
    }

    @Test
    public void stripArtistPrefixWhenMatching_supportsUnicodeEmDash_thenYearCleanup() {
        String album = DriveRepository.stripYearNoise(
                DriveRepository.stripArtistPrefixWhenMatching("Blur — (2000) The Best Of", "Blur"));
        assertEquals("The Best Of", album);
    }
}
