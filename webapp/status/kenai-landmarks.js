// Item 97b: a small, hand-picked lookup of named public landmarks along the lower Kenai River
// (mouth to roughly the KENAI_RIVER_BELUGA_LIMIT_MILES=11.5 upriver cutoff geofence.js's own
// is_whale_position_in_kenai_banner_area check already limits RED-qualifying sightings to), used
// only to turn a sighting's raw lat/lng into a human "near X" reference for the status page's RED
// state. Loaded lazily alongside Leaflet only when the status page is actually showing RED (see
// status.js), so it never costs anything on the far more common YELLOW/BLUE path.
//
// Coordinates checked against OpenStreetMap on Oct 7, 2026, each from a named OSM element (the
// original list, "from general public knowledge", had every place 2.5-7.8 km off):
//   - the river mouth: geofence.js's KENAI_RIVER_CENTERLINE[0]; the OSM Kenai River line
//     (way 221509441) ends 70 m from it.
//   - the Kenai city boat launch: OSM slipway "City of Kenai Boat Launch" (way 264691734).
//   - the Warren Ames Bridge: OSM bridge on Bridge Access Road (way 110300920), the river's
//     farthest-downriver road crossing, which Wikipedia names as the Warren Ames Memorial Bridge.
//   - Cunningham Park: OSM park node (node 9921584711).
//   - the Eagle Rock boat launch: OSM slipway "Eagle Rock Boat Launch" (way 1180992585), at about
//     river mile 11.5, the upriver beluga limit.
// Each is 0-261 m from the river centerline (the boat launches sit on the bank).
const KENAI_LANDMARKS = [
  { name: "the river mouth", lat: 60.5481177, lng: -151.2628143 },
  { name: "the Kenai city boat launch", lat: 60.54459, lng: -151.22226 },
  { name: "the Warren Ames Bridge", lat: 60.52690, lng: -151.20919 },
  { name: "Cunningham Park", lat: 60.54117, lng: -151.18355 },
  { name: "the Eagle Rock boat launch", lat: 60.54779, lng: -151.10940 }
];

// Omit the "near X" clause entirely once nothing is reasonably close. 1.5 km, not the original
// 3 km: the places above are only 2-4 km apart along the river, so 3 km named places that a
// boater wouldn't call "near".
const KENAI_LANDMARK_MAX_DISTANCE_METERS = 1500;

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
