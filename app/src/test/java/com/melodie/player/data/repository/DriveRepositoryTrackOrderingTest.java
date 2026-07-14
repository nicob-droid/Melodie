package com.melodie.player.data.repository;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DriveRepositoryTrackOrderingTest {

    @Test
    public void extractLeadingTrackNumber_ordersVinylSidesAfterPreviousSide() {
        int a1 = DriveRepository.extractLeadingTrackNumber("A1 - Wonder");
        int a2 = DriveRepository.extractLeadingTrackNumber("A2 - Drown");
        int b1 = DriveRepository.extractLeadingTrackNumber("B1 - A l'Orée du jour");

        assertEquals(1, a1);
        assertEquals(2, a2);
        assertEquals(1001, b1);
        assertTrue(a1 < a2);
        assertTrue(a2 < b1);
    }

    @Test
    public void resolveTrackSortNumber_prefersVinylFilenameOverPlainMetadata() {
        int resolved = DriveRepository.resolveTrackSortNumber("B1 - A l'Orée du jour", 1, 0);
        assertEquals(1001, resolved);
    }

    @Test
    public void resolveTrackSortNumber_keepsMetadataForClassicNumericTracks() {
        int resolved = DriveRepository.resolveTrackSortNumber("01 - Intro", 7, 1);
        assertEquals(7, resolved);
    }

    @Test
    public void stripLeadingTrackNumber_removesVinylPrefix() {
        String title = DriveRepository.stripLeadingTrackNumber("B3 - Je T'Emmènerai");
        assertEquals("Je T'Emmènerai", title);
    }
}

