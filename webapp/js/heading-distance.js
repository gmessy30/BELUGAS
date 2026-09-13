// Item 60: this file used to also carry HeadingDistance.kt/SightingRecord.kt's whale-position-
// projection math (destinationPoint) and LoggingScreen.kt's PodDirection resolution -- both
// removed along with the camera path's old review step (see submit-view.js's header comment);
// every reporting path places the whale by human map placement now, never a projected guess.
// What's left, DISTANCE_BUCKETS/distanceBucketRadiusMeters, is kept for one remaining use: the
// fixed MEDIUM/shore radius submit-view.js's proceedManualSubmit uses as its own local geofence-
// check buffer (never shown to the user, never stored on the record).

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
