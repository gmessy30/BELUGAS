package com.cookinlet.belugas

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Guards TierClaimScreen.kt's KENAI_DEPARTURE_VIEWING_AREA against drift from the real polygon
// in kenai_departure_viewing_areas (20260914000000_swap_kenai_departure_polygon_and_expose_tier_
// check.sql) -- the same duplicated-definition problem PresenceBannerSeasonGateTest.kt guards for
// the season gate, and the same fix: a test that fails loudly on an unnoticed edit, since this
// repo has no DB credentials to run the real ST_Contains check in CI to compare against directly.
//
// Can't call the server, so this can't independently confirm the two copies actually agree --
// what it CAN do is pin the CLIENT copy's ray-cast to specific expected answers for named
// coordinates, so an accidental edit to KENAI_DEPARTURE_VIEWING_AREA (a wrong paste, a dropped
// vertex, a lat/lng swap) breaks a test instead of silently shipping a visibility check that
// disagrees with the server's own enforcement.
//
// The four points below were computed by running this exact ray-cast against the current polygon
// -- not eyeballed -- then hardcoded as the pinned expectation. If the polygon in TierClaimScreen.
// kt is deliberately changed to match a real future KML update, these expected values need
// recomputing against the NEW polygon, same as the migration's own boundary needs updating in
// lockstep (see the comment on KENAI_DEPARTURE_VIEWING_AREA itself, and on
// kenai_departure_viewing_areas' boundary column server-side, both of which point back here).
class KenaiDepartureViewingAreaTest {

    @Test
    fun midRibbonPoint_isInside() {
        // Midpoint of two adjacent vertices well inside the ribbon's body (between the short
        // edge joining vertices 7 and 8 in TierClaimScreen.kt's own vertex order).
        assertTrue(
            pointInPolygon(60.5458738484591, -151.257758351735, KENAI_DEPARTURE_VIEWING_AREA),
            "midpoint of the vertex-7/8 edge should be inside the viewing area"
        )
    }

    @Test
    fun regionDefaultCenter_isOutside() {
        // Regions.COOK_INLET's own defaultCenterLat/Lng -- a generic "Kenai" reference point used
        // all over this app, but NOT itself inside this specific mapped beach strip. Worth
        // pinning explicitly since it would be an easy (wrong) assumption that the app's own
        // default center must be inside its own departure-viewing area.
        assertFalse(
            pointInPolygon(60.5544, -151.2583, KENAI_DEPARTURE_VIEWING_AREA),
            "Regions.COOK_INLET's default center is not inside the departure viewing area"
        )
    }

    @Test
    fun nearEdge_justInsideVertex7Vertex8Edge_isInside() {
        // ~22m north of the same vertex-7/8 edge midpoint above -- still inside.
        assertTrue(
            pointInPolygon(60.5460738484591, -151.257758351735, KENAI_DEPARTURE_VIEWING_AREA),
            "a point ~22m to the north of the vertex-7/8 edge should still be inside"
        )
    }

    @Test
    fun nearEdge_justOutsideVertex7Vertex8Edge_isOutside() {
        // ~22m south of that same edge midpoint -- the other side of the same edge, ~44m from
        // the "just inside" point above. Together these two pin the actual edge location, not
        // just "somewhere inside" and "somewhere outside" far from any boundary.
        assertFalse(
            pointInPolygon(60.5456738484591, -151.257758351735, KENAI_DEPARTURE_VIEWING_AREA),
            "a point ~22m to the south of the vertex-7/8 edge should be outside"
        )
    }
}
