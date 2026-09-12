// Ports HeadingDistance.kt/SightingRecord.kt's whale-position-projection math and LoggingScreen.kt's
// PodDirection, used ONLY by the camera path's review step (submit-view.js) -- ManualLoggingScreen
// has no heading/distance concept at all, its position comes directly from the map pin.

// SightingRecord.kt's DistanceBucket enum -- radii in meters, shore vs. aerial/boat observer.
const DISTANCE_BUCKETS = [
  { key: "CLOSE", label: "Close", shoreMeters: 150, aerialMeters: 300 },
  { key: "MEDIUM", label: "Medium", shoreMeters: 500, aerialMeters: 1000 },
  { key: "FAR", label: "Far", shoreMeters: 1200, aerialMeters: 3000 }
];

function distanceBucketRadiusMeters(bucketKey, isAerial) {
  const bucket = DISTANCE_BUCKETS.find((b) => b.key === bucketKey);
  return isAerial ? bucket.aerialMeters : bucket.shoreMeters;
}

// "<150m"/"~500m"/">1.2km" -- matches DistanceBucket.shortLabel exactly (comparator + distance,
// no bucket name, compact enough for three chips on one line even in this screen's forced
// landscape).
function distanceBucketShortLabel(bucketKey, isAerial) {
  const bucket = DISTANCE_BUCKETS.find((b) => b.key === bucketKey);
  const meters = isAerial ? bucket.aerialMeters : bucket.shoreMeters;
  const distanceText = meters >= 1000 ? `${meters / 1000}km` : `${meters}m`;
  const comparator = bucketKey === "CLOSE" ? "<" : bucketKey === "FAR" ? ">" : "~";
  return `${comparator}${distanceText}`;
}

// SightingRecord.kt's destinationPoint -- spherical-earth destination given a start point,
// bearing, and distance. Ported verbatim (same formula, same earth radius), not approximated
// with flat lat/lng math, for the same reason the Kotlin comment gives: 1 degree of longitude
// shrinks a lot away from the equator, and Cook Inlet sits at ~60N.
function destinationPoint(lat, lng, bearingDegrees, distanceMeters) {
  const earthRadiusMeters = 6371000.0;
  const angularDistance = distanceMeters / earthRadiusMeters;
  const bearingRad = (bearingDegrees * Math.PI) / 180.0;
  const lat1 = (lat * Math.PI) / 180.0;
  const lng1 = (lng * Math.PI) / 180.0;

  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(angularDistance) + Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearingRad)
  );
  const lng2 =
    lng1 +
    Math.atan2(
      Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(lat1),
      Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
    );

  return [(lat2 * 180.0) / Math.PI, (lng2 * 180.0) / Math.PI];
}

// LoggingScreen.kt's PodDirection.toAbsoluteTravelBearingDegrees -- resolves a relative pod-
// direction choice (relative to the observer) into the absolute bearing actually stored. Only
// the resolved absolute number is ever persisted, never the relative choice itself, matching
// native exactly.
function podDirectionToAbsoluteTravelBearingDegrees(podDirection, baseBearingDegrees) {
  if (podDirection == null || podDirection === "NONE") return null;
  if (podDirection === "AWAY") return baseBearingDegrees;
  if (podDirection === "LEFT") return ((baseBearingDegrees - 90.0 + 360.0) % 360.0);
  if (podDirection === "RIGHT") return (baseBearingDegrees + 90.0) % 360.0;
  return null;
}
