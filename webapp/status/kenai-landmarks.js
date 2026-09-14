// Item 97b: a small, hand-picked lookup of named public landmarks along the lower Kenai River
// (mouth to roughly the KENAI_RIVER_BELUGA_LIMIT_MILES=11.5 upriver cutoff geofence.js's own
// is_whale_position_in_kenai_banner_area check already limits RED-qualifying sightings to), used
// only to turn a sighting's raw lat/lng into a human "near X" reference for the status page's RED
// state. Approximate, best-effort coordinates from general public knowledge of these named public
// access points -- NOT surveyed data -- good enough for "nearest named place" purposes at the
// scale this page shows (a compact map of a several-mile river stretch), not for anything
// requiring real precision. Easy to hand-correct or extend later; loaded lazily alongside Leaflet
// only when the status page is actually showing RED (see status.js), so it never costs anything
// on the far more common YELLOW/BLUE path.
const KENAI_LANDMARKS = [
  { name: "Cunningham Park", lat: 60.5605, lng: -151.2680 },
  { name: "the Kenai boat launch", lat: 60.5578, lng: -151.2598 },
  { name: "the Warren Ames Bridge", lat: 60.5592, lng: -151.2295 },
  { name: "Eagle Rock", lat: 60.4870, lng: -151.1780 }
];

// Omit the "near X" clause entirely once nothing is reasonably close -- claiming "near Eagle
// Rock" for a sighting several miles from it would be actively misleading, not just imprecise.
const KENAI_LANDMARK_MAX_DISTANCE_METERS = 3000;

// Plain haversine -- geofence.js's own distance math (nearestSegment) is a flat-meters local
// projection tuned for short river segments, not general point-to-point distance, so this is
// intentionally separate rather than reused.
function haversineDistanceMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function nearestKenaiLandmarkName(lat, lng) {
  let best = null;
  let bestDist = Infinity;
  for (const landmark of KENAI_LANDMARKS) {
    const dist = haversineDistanceMeters(lat, lng, landmark.lat, landmark.lng);
    if (dist < bestDist) {
      bestDist = dist;
      best = landmark;
    }
  }
  if (!best || bestDist > KENAI_LANDMARK_MAX_DISTANCE_METERS) return null;
  return best.name;
}
