package com.cookinlet.belugas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Static content -- no backend needed. Facts below (hotline number, viewing-distance
// guidance, org URLs) were verified against real NOAA/ADF&G/Alaska Wildlife Alliance sources
// rather than guessed, since this is exactly the kind of content that's actively harmful if
// wrong (a wrong stranding-hotline number, a dead link).
private const val NOAA_STRANDING_HOTLINE_DISPLAY = "(877) 925-7773"
private const val NOAA_STRANDING_HOTLINE_TEL = "tel:8779257773"
private const val NOAA_STRANDING_HOTLINE_INFO_URL =
    "https://www.fisheries.noaa.gov/contact/alaska-marine-mammal-stranding-network-24-hour-hotline"
private const val NOAA_VIEWING_GUIDELINES_URL =
    "https://www.fisheries.noaa.gov/alaska/marine-life-viewing-guidelines/alaska-marine-mammal-viewing-guidelines-and-regulations"
private const val ADFG_BELUGA_URL =
    "https://www.adfg.alaska.gov/index.cfm?adfg=wildlifediversity.esalisted&id=cook-inlet-beluga-whale"
private const val ADFG_BELUGA_RESEARCH_URL =
    "https://www.adfg.alaska.gov/index.cfm?adfg=wildliferesearch.beluga"
private const val AWA_CAM_RIVER_MOUTH_URL = "https://www.youtube.com/watch?v=w7BLcV9ksFQ"
private const val AWA_CAM_RIVER_DOCK_URL = "https://www.youtube.com/watch?v=tNerDaJKjLA"
private const val AWA_YOUTUBE_URL = "https://www.youtube.com/@alaskawildlifealliance"
private const val COOK_INLET_BELUGAS_PHOTO_ID_URL = "https://www.cookinletbelugas.com"
// Both Facebook links (AKBMP, Belugas Count!) were replaced with plain website equivalents --
// tapping either opened Facebook's own app via its verified App Link, which then failed its own
// internal fallback-to-browser redispatch (logcat: "Attempting to proceed" -> a second
// startActivity with result code=-91) and silently backgrounded itself instead of opening
// anything. Not a scheme/URL issue on our side -- confirmed via logcat that our own
// startActivity call succeeds identically to every working link; the failure is entirely inside
// Facebook's app after that point.
private const val AKBMP_WEBSITE_URL = "https://akbmp.org/"
private const val NOAA_BELUGAS_COUNT_URL = "https://www.fisheries.noaa.gov/alaska/endangered-species-conservation/belugas-count"
private const val ARCHIVAL_FOOTAGE_YOUTUBE_URL = "https://www.youtube.com/@gregorymessimer"
private const val NMFS_LAW_ENFORCEMENT_DISPLAY = "(800) 853-1964"
private const val NMFS_LAW_ENFORCEMENT_TEL = "tel:8008531964"

@Composable
fun ResourcesScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    AppBackground {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RESOURCES", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                TextButton(onClick = onBack) {
                    Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                ResourceSection(title = "REPORT A STRANDING") {
                    Text(
                        "See an injured, entangled, or dead marine mammal? Call NOAA's 24-hour Alaska Marine Mammal Stranding Network hotline.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFF9800),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(NOAA_STRANDING_HOTLINE_TEL) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📞 $NOAA_STRANDING_HOTLINE_DISPLAY", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Text("TAP TO CALL", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    ResourceLinkRow("More about the stranding network →", NOAA_STRANDING_HOTLINE_INFO_URL, uriHandler)

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "SUSPICIOUS ACTIVITY / HARASSMENT",
                        color = Color.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Not a stranding -- this is a different number. Call NMFS Office of Law Enforcement.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFF9800),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(NMFS_LAW_ENFORCEMENT_TEL) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📞 $NMFS_LAW_ENFORCEMENT_DISPLAY", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Text("TAP TO CALL", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                ResourceSection(title = "KEEP YOUR DISTANCE") {
                    Text(
                        "NOAA's Alaska marine mammal viewing guidelines recommend staying at least " +
                            "100 yards from marine mammals and limiting time observing any one animal " +
                            "or group to 30 minutes. Never encircle or trap a whale between boats, or " +
                            "between a boat and shore. If a whale approaches you, put your engine in " +
                            "neutral and let it pass on its own.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    ResourceLinkRow("Full NOAA viewing guidelines →", NOAA_VIEWING_GUIDELINES_URL, uriHandler)
                }

                ResourceSection(title = "COOK INLET BELUGA WHALE PHOTO-ID PROJECT") {
                    Text(
                        "Runs a photo-identification catalog of individual Cook Inlet belugas. " +
                            "Also accepts sighting reports and high-grade photos directly through " +
                            "the site.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    ResourceLinkRow("Cook Inlet Beluga Whale Photo-ID Project →", COOK_INLET_BELUGAS_PHOTO_ID_URL, uriHandler)
                }

                ResourceSection(title = "ALASKA STATE WILDLIFE RESOURCES") {
                    ResourceLinkRow("ADF&G: Cook Inlet Beluga Whale", ADFG_BELUGA_URL, uriHandler)
                    ResourceLinkRow("ADF&G: Beluga Whale Management & Research", ADFG_BELUGA_RESEARCH_URL, uriHandler)
                }

                ResourceSection(title = "HOW TO USE THIS APP") {
                    // Longer, explanatory version of the same behavioral framing shown once as
                    // a first-run gate (AcknowledgementGateScreen.kt) -- that one is deliberately
                    // short since it's a one-time interstitial with a checkbox; this one has
                    // room to actually explain why, for anyone who wants the reasoning later.
                    Text(
                        "Many good viewing spots along the Kenai and Kasilof are on or accessed " +
                            "through private property. Always get permission before crossing a " +
                            "fence line or cutting through someone's yard to reach the water -- " +
                            "public road pullouts and posted public access points exist for a " +
                            "reason.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "When you log a sighting, this app asks for the whale's position, not " +
                            "yours -- that's deliberate. A report is far more useful to other " +
                            "observers and researchers when it marks where the animal actually " +
                            "was, so take the extra moment to estimate that rather than just " +
                            "dropping a pin on your own location.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The blue banner shows the earliest time the rising tide is deep enough " +
                            "for belugas to enter the river -- they're often visible milling in " +
                            "the inlet before that. Red means someone has actually seen them in " +
                            "the current tide cycle. Yellow is a caution: a verified sighting in " +
                            "the last few cycles, but none confirmed right now. Blue is the gate " +
                            "time -- not expected in the river before then, but worth watching " +
                            "the inlet.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                }

                ResourceSection(title = "BELUGA PHOTOGRAPHY TIPS") {
                    val tips = listOf(
                        "Use a zoom or telephoto lens rather than approaching closer -- belugas surface briefly and a long lens keeps you well within safe viewing distance.",
                        "Shoot with the sun behind you when possible; backlit water hides a pale beluga against glare.",
                        "Belugas are easiest to spot on a rising or falling tide near river mouths -- patience matters more than gear.",
                        "Burst/continuous shooting mode helps catch a surfacing that only lasts a second or two.",
                        "Skip the flash -- it does nothing at typical viewing distances and isn't worth disturbing wildlife or other observers for."
                    )
                    tips.forEach { tip ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("•  ", color = Color(0xFF00E5FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                tip,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                ResourceSection(title = "LIVE BELUGA CAMS") {
                    Text(
                        "Alaska Wildlife Alliance runs live viewing cameras at the mouth of the Kenai River.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    ResourceLinkRow("AWA Kenai River Mouth Beluga Cam (live) →", AWA_CAM_RIVER_MOUTH_URL, uriHandler)
                    ResourceLinkRow("AWA Kenai River Dock Beluga Cam (live) →", AWA_CAM_RIVER_DOCK_URL, uriHandler)
                    ResourceLinkRow("Alaska Wildlife Alliance on YouTube →", AWA_YOUTUBE_URL, uriHandler)
                }

                ResourceSection(title = "COMMUNITY & SOCIAL MEDIA") {
                    ResourceLinkRow("AKBMP →", AKBMP_WEBSITE_URL, uriHandler)
                    ResourceLinkRow("NOAA: Belugas Count →", NOAA_BELUGAS_COUNT_URL, uriHandler)
                    ResourceLinkRow("More beluga footage (archival) →", ARCHIVAL_FOOTAGE_YOUTUBE_URL, uriHandler)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
private fun ResourceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = title,
            color = Color.Yellow,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 1.dp)
}

@Composable
private fun ResourceLinkRow(label: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    Text(
        text = label,
        color = Color(0xFF00E5FF),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable { uriHandler.openUri(url) }
    )
}
