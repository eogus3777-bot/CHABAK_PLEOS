package com.chabak.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapSamplingTest {
    @Test fun keepsFullResolutionForLandscapeBackground() {
        assertEquals(1, bitmapSample(1672, 941, 1660, 940))
    }
    @Test fun samplesSquareAlbumArtworkForSmallCards() {
        assertEquals(4, bitmapSample(1254, 1254, 240, 240))
    }
    @Test fun neverUndersamplesTheAxisNeededForACrop() {
        assertEquals(1, bitmapSample(1672, 941, 900, 300))
        assertEquals(2, bitmapSample(1672, 941, 600, 440))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroTarget() {
        bitmapSample(1254, 1254, 0, 240)
    }
}
