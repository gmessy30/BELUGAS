package com.cookinlet.belugas

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

// The artwork (1179x1500, portrait, aspect ~0.786) is drawn with ContentScale.Fit, so it
// letterboxes differently depending on the container's own aspect ratio: a tall phone letterboxes
// top/bottom-ish (container narrower relative to height than the image), while a wide/tablet
// container letterboxes left/right. Both shapes are exercised here -- the whole point of this
// gesture being defined proportionally is that it has to land on the same whale in both.
class AboutHiddenGestureTest {

    // Center of the whale-B nose box, in image-pixel space -- converting a known real point into
    // a container tap and confirming it maps back, is a stronger check than only testing corners.
    private val noseCenterImageX = ABOUT_ARTWORK_INTRINSIC_WIDTH * (WHALE_B_NOSE_X_MIN + WHALE_B_NOSE_X_MAX) / 2f
    private val noseCenterImageY = ABOUT_ARTWORK_INTRINSIC_HEIGHT * (WHALE_B_NOSE_Y_MIN + WHALE_B_NOSE_Y_MAX) / 2f

    private fun tapAtImagePoint(
        imageX: Float,
        imageY: Float,
        containerWidth: Float,
        containerHeight: Float
    ): NormalizedArtworkPoint? {
        val scale = minOf(
            containerWidth / ABOUT_ARTWORK_INTRINSIC_WIDTH,
            containerHeight / ABOUT_ARTWORK_INTRINSIC_HEIGHT
        )
        val drawnWidth = ABOUT_ARTWORK_INTRINSIC_WIDTH * scale
        val drawnHeight = ABOUT_ARTWORK_INTRINSIC_HEIGHT * scale
        val letterboxX = (containerWidth - drawnWidth) / 2f
        val letterboxY = (containerHeight - drawnHeight) / 2f

        val tapX = letterboxX + imageX * scale
        val tapY = letterboxY + imageY * scale
        return normalizedArtworkPoint(tapX, tapY, containerWidth, containerHeight)
    }

    @Test
    fun noseCenter_hitsOnTallPhoneAspectRatio() {
        // 1080x2400 (~9:20), much taller/narrower than the artwork -- letterboxes top/bottom.
        val point = tapAtImagePoint(noseCenterImageX, noseCenterImageY, 1080f, 2400f)
        assertNotNull(point)
        assertTrue(isWithinWhaleBNose(point), "expected nose center to register on a tall phone aspect ratio, got $point")
    }

    @Test
    fun noseCenter_hitsOnWideTabletAspectRatio() {
        // 1600x1000 (16:10 landscape), wider than the artwork -- letterboxes left/right.
        val point = tapAtImagePoint(noseCenterImageX, noseCenterImageY, 1600f, 1000f)
        assertNotNull(point)
        assertTrue(isWithinWhaleBNose(point), "expected nose center to register on a wide tablet aspect ratio, got $point")
    }

    @Test
    fun noseCenter_hitsOnSquareContainer() {
        // 1000x1000 -- neither dimension matches the artwork's own ratio; exercises both
        // letterbox axes' math being exactly zero margin on neither side.
        val point = tapAtImagePoint(noseCenterImageX, noseCenterImageY, 1000f, 1000f)
        assertNotNull(point)
        assertTrue(isWithinWhaleBNose(point), "expected nose center to register on a square container, got $point")
    }

    @Test
    fun tapOutsideDrawnImage_inLetterboxMargin_isNull() {
        // On a very wide container, the far-left edge is pure letterbox margin, not artwork.
        val point = normalizedArtworkPoint(
            tapX = 1f,
            tapY = 500f,
            containerWidth = 1600f,
            containerHeight = 1000f
        )
        assertNull(point, "a tap in the letterbox margin should not resolve to an artwork point")
    }

    @Test
    fun tapOnOtherWhale_missesNoseBox_onBothAspectRatios() {
        // Whale A's rough position on the sheet (bottom-right cluster) -- should never register,
        // on either a tall or a wide container.
        val whaleARoughImageX = 950f
        val whaleARoughImageY = 1300f

        val tall = tapAtImagePoint(whaleARoughImageX, whaleARoughImageY, 1080f, 2400f)
        assertNotNull(tall)
        assertFalse(isWithinWhaleBNose(tall))

        val wide = tapAtImagePoint(whaleARoughImageX, whaleARoughImageY, 1600f, 1000f)
        assertNotNull(wide)
        assertFalse(isWithinWhaleBNose(wide))
    }

    @Test
    fun noseBoxCorners_areConsistentAcrossAspectRatios() {
        // The normalized box itself must be identical regardless of container shape -- only the
        // container-space tap coordinates that map into it should differ.
        val tallMin = tapAtImagePoint(
            ABOUT_ARTWORK_INTRINSIC_WIDTH * WHALE_B_NOSE_X_MIN,
            ABOUT_ARTWORK_INTRINSIC_HEIGHT * WHALE_B_NOSE_Y_MIN,
            1080f, 2400f
        )
        val wideMin = tapAtImagePoint(
            ABOUT_ARTWORK_INTRINSIC_WIDTH * WHALE_B_NOSE_X_MIN,
            ABOUT_ARTWORK_INTRINSIC_HEIGHT * WHALE_B_NOSE_Y_MIN,
            1600f, 1000f
        )
        assertNotNull(tallMin)
        assertNotNull(wideMin)
        assertTrue(kotlin.math.abs(tallMin.x - wideMin.x) < 0.001f)
        assertTrue(kotlin.math.abs(tallMin.y - wideMin.y) < 0.001f)
    }
}
