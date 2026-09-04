package com.cookinlet.belugas

import kotlin.math.min

// Geometry for the hidden tier-claim gesture's tap target (see AboutScreen's own gesture-state
// comment) -- pulled out of AboutScreen.kt so the letterbox math below can be exercised by a real
// unit test (AboutHiddenGestureTest) independent of Compose, rather than only eyeballed.

// beluga_about_sketches.jpg's real pixel dimensions (confirmed via the source file itself, not
// assumed) -- AppBackground draws it with ContentScale.Fit, which letterboxes rather than crops,
// so the drawn image's on-screen rect depends on both this intrinsic aspect ratio and whatever
// size the container actually is.
internal const val ABOUT_ARTWORK_INTRINSIC_WIDTH = 1179f
internal const val ABOUT_ARTWORK_INTRINSIC_HEIGHT = 1500f

// Whale B's nose (mouth/teeth), as a proportion of the artwork's own bounds -- not fixed pixel
// coordinates -- so it lands on the same whale regardless of screen size/aspect ratio. Measured
// directly off the source JPG's pixels (a 150x150px box at (635,840) in the 1179x1500 original,
// framing the open mouth with clear margin from the "B" label, the eye, and whale A), not
// eyeballed off a rendered composite. See the artwork itself -- Luna's reference sheet labels the
// whales "A" and "B" right in the image; whale B is the open-mouthed one in the middle-right.
internal const val WHALE_B_NOSE_X_MIN = 635f / ABOUT_ARTWORK_INTRINSIC_WIDTH
internal const val WHALE_B_NOSE_X_MAX = 785f / ABOUT_ARTWORK_INTRINSIC_WIDTH
internal const val WHALE_B_NOSE_Y_MIN = 840f / ABOUT_ARTWORK_INTRINSIC_HEIGHT
internal const val WHALE_B_NOSE_Y_MAX = 990f / ABOUT_ARTWORK_INTRINSIC_HEIGHT

internal data class NormalizedArtworkPoint(val x: Float, val y: Float)

/**
 * Undoes ContentScale.Fit's letterboxing to find where a raw tap (in container-local pixels)
 * lands within the artwork's own 0..1 coordinate space. Fit scales the whole image down to the
 * largest size that fits the container without cropping, then centers it -- the container itself
 * is whatever size the screen is, but the drawn image is usually smaller on one axis, with equal
 * margins (the "letterbox") on either side of that axis. Naively dividing tap offset by container
 * size (skipping this) would be correct only on a screen whose aspect ratio happens to exactly
 * match the artwork's own -- wrong on essentially every real device.
 *
 * Returns null if the tap landed in the letterbox margin itself, i.e. genuinely outside the
 * drawn image (there's no artwork there to have tapped).
 */
internal fun normalizedArtworkPoint(
    tapX: Float,
    tapY: Float,
    containerWidth: Float,
    containerHeight: Float,
    intrinsicWidth: Float = ABOUT_ARTWORK_INTRINSIC_WIDTH,
    intrinsicHeight: Float = ABOUT_ARTWORK_INTRINSIC_HEIGHT
): NormalizedArtworkPoint? {
    if (containerWidth <= 0f || containerHeight <= 0f) return null

    val scale = min(containerWidth / intrinsicWidth, containerHeight / intrinsicHeight)
    val drawnWidth = intrinsicWidth * scale
    val drawnHeight = intrinsicHeight * scale
    val letterboxX = (containerWidth - drawnWidth) / 2f
    val letterboxY = (containerHeight - drawnHeight) / 2f

    val imageX = tapX - letterboxX
    val imageY = tapY - letterboxY
    if (imageX < 0f || imageX > drawnWidth || imageY < 0f || imageY > drawnHeight) return null

    return NormalizedArtworkPoint(imageX / drawnWidth, imageY / drawnHeight)
}

internal fun isWithinWhaleBNose(point: NormalizedArtworkPoint): Boolean =
    point.x in WHALE_B_NOSE_X_MIN..WHALE_B_NOSE_X_MAX && point.y in WHALE_B_NOSE_Y_MIN..WHALE_B_NOSE_Y_MAX
