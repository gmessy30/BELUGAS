// Geofence check -- ports GeofenceUtils.kt + CoastlineGeometry.kt's real-water verification
// faithfully, not approximated. This runs the same PIN-position check ManualLoggingScreen.kt
// uses (isWithinOuterGeofence hard reject, then isWhalePositionVerified's buffer-distance check
// against real coastline/river data) -- both this app's camera-path and manual-path submits use
// PIN semantics, so neither needs LoggingScreen's separate heading/distance-projection geometry
// (computeDefaultOffshoreHeadingDegrees/projectOffshoreFallback), which this file deliberately
// does not port -- consistent with the earlier decision not to port LoggingScreen's PROJECTED/
// FALLBACK position math at all.
//
// All coordinate data below (WELL_SOURCED_ZONES' polygons, the two river centerlines) was
// extracted programmatically from CoastlineGeometry.kt's own source rather than hand-transcribed,
// to eliminate transcription error across roughly 700 coordinate pairs -- vertex counts cross-
// checked against that file's own per-zone comments before use.

// Coarse OUTER geofence -- GeofenceUtils.OUTER_GEOFENCE_MIN/MAX_LAT/LNG exactly. A hard,
// immediate reject with no override: "is this even remotely Cook Inlet," not precision.
const OUTER_GEOFENCE_MIN_LAT = 59.0;
const OUTER_GEOFENCE_MAX_LAT = 61.8;
const OUTER_GEOFENCE_MIN_LNG = -154.2;
const OUTER_GEOFENCE_MAX_LNG = -148.0;

function isWithinOuterGeofence(lat, lng) {
  return lat >= OUTER_GEOFENCE_MIN_LAT && lat <= OUTER_GEOFENCE_MAX_LAT &&
    lng >= OUTER_GEOFENCE_MIN_LNG && lng <= OUTER_GEOFENCE_MAX_LNG;
}

// Ceiling on the buffer used to verify a whale position, regardless of the row's own
// uncertainty_radius_meters -- matches GeofenceUtils.WHALE_POSITION_VERIFY_BUFFER_CAP_METERS.
// Without this, claiming more uncertainty would make verification easier, a perverse incentive.
const WHALE_POSITION_VERIFY_BUFFER_CAP_METERS = 1000.0;

// DistanceBucket.MEDIUM.radiusMeters(isAerial = false) -- ManualLoggingScreen's own fixed local
// buffer for the PIN geofence check (never stored, only used for this check itself, same as
// native: local to this check only, never shown to the user, never saved to the record).
const MANUAL_PIN_GEOFENCE_CHECK_RADIUS_METERS = 500.0;

const ZONE_AUTHORITATIVE_REACH_KM = 25.0;
const METERS_PER_DEGREE_LAT = 111320.0;

function metersPerDegreeLng(lat) {
  return METERS_PER_DEGREE_LAT * Math.cos(lat * Math.PI / 180.0);
}

// Standard PNPOLY ray-casting test, operating directly on lat/lng as a planar (lng=x, lat=y)
// pair -- matches pointInPolygon in CoastlineGeometry.kt exactly.
function pointInPolygon(lat, lng, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const latI = ring[i][0], lngI = ring[i][1];
    const latJ = ring[j][0], lngJ = ring[j][1];
    if ((lngI > lng) !== (lngJ > lng)) {
      const intersectLat = latI + (lng - lngI) / (lngJ - lngI) * (latJ - latI);
      if (lat < intersectLat) inside = !inside;
    }
  }
  return inside;
}

function initialBearingDegrees(lat1, lng1, lat2, lng2) {
  const phi1 = lat1 * Math.PI / 180.0;
  const phi2 = lat2 * Math.PI / 180.0;
  const deltaLambda = (lng2 - lng1) * Math.PI / 180.0;
  const y = Math.sin(deltaLambda) * Math.cos(phi2);
  const x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);
  const theta = Math.atan2(y, x);
  return (theta * 180.0 / Math.PI + 360.0) % 360.0;
}

// Nearest point across every segment of the polyline (not just nearest vertex), via a local
// flat-meters projection -- matches nearestSegment in CoastlineGeometry.kt exactly.
function nearestSegment(lat, lng, polyline) {
  if (polyline.length < 2) return null;
  let best = null;
  let bestDistSq = Infinity;
  const mPerLng = metersPerDegreeLng(lat);

  for (let i = 0; i < polyline.length - 1; i++) {
    const lat1 = polyline[i][0], lng1 = polyline[i][1];
    const lat2 = polyline[i + 1][0], lng2 = polyline[i + 1][1];

    const x1 = lng1 * mPerLng, y1 = lat1 * METERS_PER_DEGREE_LAT;
    const x2 = lng2 * mPerLng, y2 = lat2 * METERS_PER_DEGREE_LAT;
    const px = lng * mPerLng, py = lat * METERS_PER_DEGREE_LAT;

    const dx = x2 - x1, dy = y2 - y1;
    const lenSq = dx * dx + dy * dy;
    let t = lenSq > 0.0 ? ((px - x1) * dx + (py - y1) * dy) / lenSq : 0.0;
    t = Math.max(0.0, Math.min(1.0, t));

    const nearX = x1 + t * dx, nearY = y1 + t * dy;
    const distSq = (px - nearX) * (px - nearX) + (py - nearY) * (py - nearY);

    if (distSq < bestDistSq) {
      bestDistSq = distSq;
      best = {
        nearestLat: nearY / METERS_PER_DEGREE_LAT,
        nearestLng: nearX / mPerLng,
        bearingDegrees: initialBearingDegrees(lat1, lng1, lat2, lng2),
        distanceMeters: Math.sqrt(distSq)
      };
    }
  }
  return best;
}

// Extracted programmatically from CoastlineGeometry.kt's WELL_SOURCED_ZONES (KENAI,
// LOWER_INLET_SOUTH, SHIP_CREEK_KNIK_ARM_ANCHORAGE, TURNAGAIN_ARM_NORTHERN, TURNAGAIN_ARM_MID,
// TURNAGAIN_ARM_UPPER) -- turnagain_arm_southern/susitna_delta are NOT included, matching native
// (neither is a faithful coastline trace there -- see that file's own comment).
const WELL_SOURCED_ZONES = [
  {
    slug: "kenai",
    fullRing: [
      [60.7366758, -151.5676882],
      [60.7368572, -151.346916],
      [60.7261008, -151.3937317],
      [60.7191148, -151.4101289],
      [60.6935775, -151.4014373],
      [60.6759362, -151.3878076],
      [60.6544696, -151.3632285],
      [60.6322871, -151.3492429],
      [60.5809819, -151.3298644],
      [60.5630859, -151.3050015],
      [60.5553444, -151.2833585],
      [60.5486469, -151.2621273],
      [60.5406886, -151.2327182],
      [60.5250928, -151.2266779],
      [60.5212876, -151.173144],
      [60.5459337, -151.1252208],
      [60.5212876, -151.173144],
      [60.5250928, -151.2266779],
      [60.5406886, -151.2327182],
      [60.5486469, -151.2621273],
      [60.5276312, -151.2730405],
      [60.4860598, -151.2803865],
      [60.4673916, -151.2826138],
      [60.4284368, -151.2884557],
      [60.3997196, -151.295078],
      [60.3860366, -151.3021596],
      [60.3424351, -151.2890138],
      [60.3172346, -151.2621582],
      [60.3102738, -151.2475703],
      [60.305684, -151.222275],
      [60.3102738, -151.2475703],
      [60.3172346, -151.2621582],
      [60.3424351, -151.2890138],
      [60.3860366, -151.3021596],
      [60.3828618, -151.3218588],
      [60.3753466, -151.3527568],
      [60.3429598, -151.3819584],
      [60.3176174, -151.3816645],
      [60.3174391, -151.5995967],
      [60.7366758, -151.5676882],
    ],
    coastlineOnly: [
      [60.7368572, -151.346916],
      [60.7261008, -151.3937317],
      [60.7191148, -151.4101289],
      [60.6935775, -151.4014373],
      [60.6759362, -151.3878076],
      [60.6544696, -151.3632285],
      [60.6322871, -151.3492429],
      [60.5809819, -151.3298644],
      [60.5630859, -151.3050015],
      [60.5553444, -151.2833585],
      [60.5486469, -151.2621273],
      [60.5276312, -151.2730405],
      [60.4860598, -151.2803865],
      [60.4673916, -151.2826138],
      [60.4284368, -151.2884557],
      [60.3997196, -151.295078],
      [60.3860366, -151.3021596],
      [60.3828618, -151.3218588],
      [60.3753466, -151.3527568],
      [60.3429598, -151.3819584],
      [60.3176174, -151.3816645],
    ]
  },
  {
    slug: "lower_inlet_south",
    fullRing: [
      [60.3751679, -151.5710751],
      [60.3753466, -151.3527568],
      [60.1824602, -151.4675938],
      [60.0831595, -151.6290098],
      [60.0326754, -151.6998027],
      [59.8856007, -151.7935463],
      [59.7353228, -151.8433034],
      [59.6750709, -151.4041742],
      [59.6491266, -151.6373241],
      [59.6424097, -151.4669234],
      [59.6385074, -151.5465474],
      [59.6379875, -151.5001532],
      [59.6357842, -151.5291233],
      [59.6300155, -151.4923374],
      [59.6208577, -151.4549495],
      [59.6164695, -151.4489685],
      [59.6105027, -151.439624],
      [59.6091295, -151.4361317],
      [59.6035425, -151.4248518],
      [59.5518838, -151.3713133],
      [59.5461827, -151.3732553],
      [59.4506606, -151.5609226],
      [60.3751679, -151.5710751],
    ],
    coastlineOnly: [
      [60.3753466, -151.3527568],
      [60.1824602, -151.4675938],
      [60.0831595, -151.6290098],
      [60.0326754, -151.6998027],
      [59.8856007, -151.7935463],
      [59.7353228, -151.8433034],
      [59.6750709, -151.4041742],
      [59.6491266, -151.6373241],
      [59.6424097, -151.4669234],
      [59.6385074, -151.5465474],
      [59.6379875, -151.5001532],
      [59.6357842, -151.5291233],
      [59.6300155, -151.4923374],
      [59.6208577, -151.4549495],
      [59.6164695, -151.4489685],
      [59.6105027, -151.439624],
      [59.6091295, -151.4361317],
      [59.6035425, -151.4248518],
      [59.5518838, -151.3713133],
      [59.5461827, -151.3732553],
    ]
  },
  {
    slug: "ship_creek_knik_arm_anchorage",
    fullRing: [
      [61.0501897, -149.7958923],
      [61.0702701, -149.8270671],
      [61.0901281, -149.8686609],
      [61.1102239, -149.9268614],
      [61.130565, -149.9716155],
      [61.20936, -149.9234689],
      [61.2253725, -149.8916513],
      [61.2498793, -149.8825525],
      [61.2670324, -149.8637985],
      [61.289595, -149.838277],
      [61.307397, -149.8189027],
      [61.3187581, -149.8009818],
      [61.3198466, -149.9185563],
      [61.2905041, -149.9181032],
      [61.273568, -149.9222257],
      [61.2502319, -149.9614696],
      [61.2491436, -150.0486695],
      [61.2100046, -149.9233488],
      [61.1911934, -150.0281811],
      [61.1773396, -150.0490272],
      [61.1477858, -150.0488381],
      [61.1299173, -149.9705297],
      [61.1097138, -149.924501],
      [61.0895569, -149.8654364],
      [61.0694978, -149.8260286],
      [61.0501897, -149.7958923],
    ],
    coastlineOnly: [
      [61.0501897, -149.7958923],
      [61.0702701, -149.8270671],
      [61.0901281, -149.8686609],
      [61.1102239, -149.9268614],
      [61.130565, -149.9716155],
      [61.20936, -149.9234689],
      [61.2253725, -149.8916513],
      [61.2498793, -149.8825525],
      [61.2670324, -149.8637985],
      [61.289595, -149.838277],
      [61.307397, -149.8189027],
      [61.3187581, -149.8009818],
      [61.3198466, -149.9185563],
      [61.2905041, -149.9181032],
      [61.273568, -149.9222257],
      [61.2502319, -149.9614696],
      [61.2491436, -150.0486695],
      [61.2100046, -149.9233488],
      [61.1911934, -150.0281811],
      [61.1773396, -150.0490272],
      [61.1477858, -150.0488381],
      [61.1299173, -149.9705297],
      [61.1097138, -149.924501],
      [61.0895569, -149.8654364],
      [61.0694978, -149.8260286],
    ]
  },
  {
    slug: "turnagain_arm_northern",
    fullRing: [
      [60.9501879, -149.8956907],
      [60.9687547, -149.8290968],
      [61.0175752, -149.7397115],
      [60.999475, -149.6435683],
      [60.9838916, -149.5311968],
      [60.9727474, -149.4671907],
      [60.9460508, -149.3796517],
      [60.8946491, -149.3539654],
      [60.9042512, -149.4400256],
      [60.9256969, -149.5306776],
      [60.9209726, -149.6458563],
      [60.9554544, -149.7182679],
      [60.966193, -149.8250628],
      [60.9248332, -149.9186612],
      [60.9501879, -149.8956907],
    ],
    coastlineOnly: [
      [60.9501879, -149.8956907],
      [60.9687547, -149.8290968],
      [61.0175752, -149.7397115],
      [60.999475, -149.6435683],
      [60.9838916, -149.5311968],
      [60.9727474, -149.4671907],
      [60.9460508, -149.3796517],
      [60.8946491, -149.3539654],
      [60.9042512, -149.4400256],
      [60.9256969, -149.5306776],
      [60.9209726, -149.6458563],
      [60.9554544, -149.7182679],
      [60.966193, -149.8250628],
      [60.9248332, -149.9186612],
    ]
  },
  {
    slug: "turnagain_arm_mid",
    fullRing: [
      [60.9460508, -149.3796517],
      [60.9370152, -149.2623142],
      [60.9448624, -149.1938707],
      [60.9135708, -149.09919],
      [60.8583638, -149.0162893],
      [60.8197676, -149.0002884],
      [60.8642041, -149.0811245],
      [60.886884, -149.1737297],
      [60.8947429, -149.2741072],
      [60.8946491, -149.3539654],
      [60.9460508, -149.3796517],
    ],
    coastlineOnly: [
      [60.9460508, -149.3796517],
      [60.9370152, -149.2623142],
      [60.9448624, -149.1938707],
      [60.9135708, -149.09919],
      [60.8583638, -149.0162893],
      [60.8197676, -149.0002884],
      [60.8642041, -149.0811245],
      [60.886884, -149.1737297],
      [60.8947429, -149.2741072],
      [60.8946491, -149.3539654],
    ]
  },
  {
    slug: "turnagain_arm_upper",
    fullRing: [
      [60.9221044, -149.1389043],
      [60.9012904, -149.0748797],
      [60.8583638, -149.0162893],
      [60.8417766, -149.0075211],
      [60.8197676, -149.0002884],
      [60.8642041, -149.0811245],
      [60.9221044, -149.1389043],
    ],
    coastlineOnly: [
      [60.9221044, -149.1389043],
      [60.9012904, -149.0748797],
      [60.8583638, -149.0162893],
      [60.8417766, -149.0075211],
      [60.8197676, -149.0002884],
      [60.8642041, -149.0811245],
    ]
  }
];

// Full raw KPB Anadromous Streams centerlines (Kenai 159 / Kasilof 81 points) -- extracted
// programmatically from CoastlineGeometry.kt's own KENAI_RIVER_CENTERLINE/KASILOF_RIVER_CENTERLINE.
const KENAI_RIVER_CENTERLINE = [
  [60.5481177, -151.2628143],
  [60.5510267, -151.2419519],
  [60.5497456, -151.2337908],
  [60.5459943, -151.2258416],
  [60.5439376, -151.2258036],
  [60.5421754, -151.2282353],
  [60.5398358, -151.2468233],
  [60.5371988, -151.2525346],
  [60.5349798, -151.2530788],
  [60.5311711, -151.2508361],
  [60.5226364, -151.2449343],
  [60.5215422, -151.2425341],
  [60.5211004, -151.2379416],
  [60.5244092, -151.2310349],
  [60.5263248, -151.2224648],
  [60.5268041, -151.2093148],
  [60.5279486, -151.2005954],
  [60.5302507, -151.1973329],
  [60.5381515, -151.1935145],
  [60.5400222, -151.1879357],
  [60.5401742, -151.1835035],
  [60.5395024, -151.1780025],
  [60.5383723, -151.1756063],
  [60.5366998, -151.1756094],
  [60.5308466, -151.1806792],
  [60.5262745, -151.1786541],
  [60.5224669, -151.1748533],
  [60.5196138, -151.1700443],
  [60.5183945, -151.1633839],
  [60.5186212, -151.1592328],
  [60.5206685, -151.155492],
  [60.525622, -151.1574089],
  [60.5288189, -151.1569863],
  [60.5319992, -151.1504375],
  [60.5390748, -151.1458952],
  [60.5397573, -151.1352862],
  [60.5447552, -151.1272196],
  [60.5472054, -151.120192],
  [60.5473258, -151.1151184],
  [60.5467502, -151.1136826],
  [60.5435541, -151.1101215],
  [60.5414395, -151.1055714],
  [60.5328554, -151.098321],
  [60.5231463, -151.0957453],
  [60.5159314, -151.0999577],
  [60.5103917, -151.0898617],
  [60.5091304, -151.0902802],
  [60.5086425, -151.1023873],
  [60.5141038, -151.1237396],
  [60.5139252, -151.1272184],
  [60.5118365, -151.1313584],
  [60.5081235, -151.1333546],
  [60.5061884, -151.1306341],
  [60.5055691, -151.1272181],
  [60.5060685, -151.1148321],
  [60.5027105, -151.1080966],
  [60.4995259, -151.105581],
  [60.4943084, -151.1057478],
  [60.491138, -151.1117697],
  [60.4884339, -151.1224899],
  [60.4825844, -151.1267128],
  [60.4762551, -151.1185796],
  [60.4751841, -151.1141851],
  [60.476536, -151.108827],
  [60.4823623, -151.1013234],
  [60.4833532, -151.0962453],
  [60.4804939, -151.0882686],
  [60.4768021, -151.0813657],
  [60.4761064, -151.0774373],
  [60.4816009, -151.0659663],
  [60.4832019, -151.0575501],
  [60.479933, -151.0286971],
  [60.48095, -151.0193734],
  [60.4836449, -151.0141362],
  [60.4853366, -151.0077429],
  [60.4838025, -150.9991515],
  [60.4812312, -150.9934013],
  [60.4754617, -150.9867012],
  [60.4741742, -150.9866155],
  [60.4688324, -150.9743473],
  [60.4660992, -150.966871],
  [60.462211, -150.9515249],
  [60.4592831, -150.9463142],
  [60.4598518, -150.9390302],
  [60.4623167, -150.934559],
  [60.4659352, -150.932472],
  [60.4754412, -150.9205984],
  [60.4761134, -150.9125519],
  [60.475152, -150.9079036],
  [60.4756138, -150.9013736],
  [60.4835755, -150.8809016],
  [60.4911147, -150.8686965],
  [60.4912005, -150.8649955],
  [60.4988901, -150.8614558],
  [60.5030555, -150.8554715],
  [60.5080397, -150.8521817],
  [60.5112, -150.8481814],
  [60.5130516, -150.8407615],
  [60.512969, -150.8341204],
  [60.5088637, -150.8255087],
  [60.510044, -150.8019015],
  [60.5122142, -150.7897192],
  [60.517092, -150.7819952],
  [60.5227236, -150.7786283],
  [60.5256408, -150.7736083],
  [60.5288147, -150.7611699],
  [60.5321326, -150.7608386],
  [60.5355916, -150.7575104],
  [60.5315174, -150.7451606],
  [60.5281286, -150.742987],
  [60.5240168, -150.744836],
  [60.5194686, -150.7431383],
  [60.5179197, -150.7395844],
  [60.5178948, -150.7361167],
  [60.5200889, -150.7324256],
  [60.5226024, -150.7249918],
  [60.5221626, -150.7213262],
  [60.5209282, -150.7186985],
  [60.5183357, -150.7180034],
  [60.515779, -150.7139545],
  [60.514528, -150.7058253],
  [60.5154424, -150.7025018],
  [60.5149407, -150.698724],
  [60.511884, -150.6958046],
  [60.5097734, -150.6908752],
  [60.5083809, -150.6841983],
  [60.5061882, -150.6831065],
  [60.5031867, -150.6848485],
  [60.5013414, -150.6812097],
  [60.5003011, -150.6674423],
  [60.4958229, -150.6524594],
  [60.4916633, -150.653193],
  [60.4903658, -150.6429657],
  [60.4894875, -150.6226394],
  [60.4873434, -150.6204246],
  [60.4865428, -150.6272116],
  [60.4819781, -150.6283369],
  [60.480152, -150.6222391],
  [60.4813918, -150.6091619],
  [60.4770725, -150.59758],
  [60.4761908, -150.5921891],
  [60.4753455, -150.5912116],
  [60.4724856, -150.5931869],
  [60.4688492, -150.6028569],
  [60.4667772, -150.6035303],
  [60.4637093, -150.6001969],
  [60.4669104, -150.5924983],
  [60.4656504, -150.5824838],
  [60.4633659, -150.5776997],
  [60.4587204, -150.5798633],
  [60.4575648, -150.5725258],
  [60.4610027, -150.5639422],
  [60.4622074, -150.5438701],
  [60.4613011, -150.5373716],
  [60.4615897, -150.5326436],
  [60.4634852, -150.5306722],
  [60.4657762, -150.5311251],
  [60.4677441, -150.5240942],
  [60.4689391, -150.512545],
];

const KASILOF_RIVER_CENTERLINE = [
  [60.3856162, -151.300104],
  [60.3851859, -151.2913394],
  [60.3837797, -151.2883267],
  [60.3821861, -151.2882356],
  [60.3799965, -151.2914022],
  [60.3794018, -151.2976905],
  [60.376571, -151.3033092],
  [60.3737054, -151.3038826],
  [60.3685425, -151.3020216],
  [60.3673387, -151.299699],
  [60.3673045, -151.2963377],
  [60.3687538, -151.2944009],
  [60.3720499, -151.2938583],
  [60.3724535, -151.2892489],
  [60.3715547, -151.2873048],
  [60.3691891, -151.2864249],
  [60.3644843, -151.2909202],
  [60.361773, -151.2909435],
  [60.3594638, -151.2829959],
  [60.3581506, -151.2818272],
  [60.3522034, -151.2837348],
  [60.3470668, -151.2881295],
  [60.3454012, -151.2875282],
  [60.3425153, -151.2890662],
  [60.3365081, -151.282998],
  [60.3348942, -151.2833157],
  [60.3324212, -151.2883375],
  [60.3267265, -151.2898686],
  [60.3226406, -151.2809922],
  [60.3220638, -151.2748857],
  [60.3211236, -151.2725453],
  [60.3179949, -151.2697734],
  [60.3166063, -151.2556281],
  [60.3168652, -151.2501267],
  [60.3162251, -151.2484025],
  [60.3134417, -151.2523978],
  [60.312806, -151.2514124],
  [60.3126847, -151.2452379],
  [60.3094959, -151.2479304],
  [60.3072272, -151.2445573],
  [60.3038368, -151.2469933],
  [60.3029891, -151.2443785],
  [60.3038916, -151.241548],
  [60.3064392, -151.2416016],
  [60.3093899, -151.2355091],
  [60.3089641, -151.2330584],
  [60.3080636, -151.2323268],
  [60.3049277, -151.2345829],
  [60.3039056, -151.2325481],
  [60.3034006, -151.2272857],
  [60.3047964, -151.222802],
  [60.3085008, -151.2228231],
  [60.3093286, -151.2196631],
  [60.3084746, -151.2165346],
  [60.3044559, -151.2159139],
  [60.3017636, -151.2136586],
  [60.2994874, -151.2137628],
  [60.2967597, -151.2163678],
  [60.2927757, -151.2140217],
  [60.2911514, -151.2154356],
  [60.2872581, -151.2238887],
  [60.2864355, -151.2240928],
  [60.2854526, -151.2218078],
  [60.2862187, -151.2151363],
  [60.2846268, -151.2133736],
  [60.2827138, -151.2082465],
  [60.2771431, -151.219312],
  [60.2750534, -151.2078682],
  [60.2753086, -151.2057802],
  [60.273668, -151.2016263],
  [60.2701572, -151.1995963],
  [60.2661146, -151.1914725],
  [60.2614142, -151.1912722],
  [60.2602527, -151.189494],
  [60.2595717, -151.1853944],
  [60.2557543, -151.1815377],
  [60.2558728, -151.1716172],
  [60.2494891, -151.1729862],
  [60.2464218, -151.176536],
  [60.240629, -151.1699975],
  [60.2329842, -151.1653133],
];

const KENAI_RIVER_BELUGA_LIMIT_MILES = 11.5;
const KASILOF_RIVER_BELUGA_LIMIT_MILES = 7.5;
const METERS_PER_MILE = 1609.344;

// Matches truncateAtRiverMiles exactly: walks cumulative distance from the mouth (index 0),
// interpolating the exact cut vertex rather than snapping to the nearest existing point.
function truncateAtRiverMiles(centerline, miles) {
  if (centerline.length < 2) return centerline;
  const targetMeters = miles * METERS_PER_MILE;

  const result = [centerline[0]];
  let cumMeters = 0.0;
  for (let i = 0; i < centerline.length - 1; i++) {
    const lat1 = centerline[i][0], lng1 = centerline[i][1];
    const lat2 = centerline[i + 1][0], lng2 = centerline[i + 1][1];
    const mPerLng = metersPerDegreeLng((lat1 + lat2) / 2.0);
    const dx = (lng2 - lng1) * mPerLng;
    const dy = (lat2 - lat1) * METERS_PER_DEGREE_LAT;
    const segMeters = Math.sqrt(dx * dx + dy * dy);

    if (cumMeters + segMeters >= targetMeters) {
      const t = segMeters > 0.0 ? (targetMeters - cumMeters) / segMeters : 0.0;
      result.push([lat1 + t * (lat2 - lat1), lng1 + t * (lng2 - lng1)]);
      return result;
    }
    cumMeters += segMeters;
    result.push(centerline[i + 1]);
  }
  return result;
}

// Matches KENAI_RIVER_CENTERLINE_BELUGA_LIMIT/KASILOF_RIVER_CENTERLINE_BELUGA_LIMIT -- computed
// once, at module load, same as native's top-level vals.
const KENAI_RIVER_CENTERLINE_BELUGA_LIMIT = truncateAtRiverMiles(KENAI_RIVER_CENTERLINE, KENAI_RIVER_BELUGA_LIMIT_MILES);
const KASILOF_RIVER_CENTERLINE_BELUGA_LIMIT = truncateAtRiverMiles(KASILOF_RIVER_CENTERLINE, KASILOF_RIVER_BELUGA_LIMIT_MILES);

// Matches realLinesForZone: every real linestring a zone can be measured against for the
// buffer-distance fallback -- kenai additionally gets its two beluga-limit-truncated river
// centerlines (its polygon's own river spikes are zero-width, so containment alone can never
// validate a point near either river).
function realLinesForZone(zone) {
  const lines = [zone.coastlineOnly];
  if (zone.slug === "kenai") {
    lines.push(KENAI_RIVER_CENTERLINE_BELUGA_LIMIT);
    lines.push(KASILOF_RIVER_CENTERLINE_BELUGA_LIMIT);
  }
  return lines;
}

// Matches isWithinGeofenceBuffer exactly: three-tier real-data check --
//  1. Inside a zone's real water polygon -> true, unambiguous.
//  2. Outside every polygon, but within bufferMeters of the nearest real coastline/river-
//     centerline segment -> true.
//  3. Neither, but still within ZONE_AUTHORITATIVE_REACH_KM of a well-sourced zone -> a
//     confident false (real data exists here and it is genuinely too far from water).
//  4. Out of reach of every well-sourced zone -> null (defer to a coarser check/fallback).
function isWithinGeofenceBuffer(lat, lng, bufferMeters) {
  const bufferKm = bufferMeters / 1000.0;
  let withinReachOfAnyZone = false;

  for (const zone of WELL_SOURCED_ZONES) {
    if (pointInPolygon(lat, lng, zone.fullRing)) return true;

    let nearestKm = Infinity;
    for (const line of realLinesForZone(zone)) {
      const segment = nearestSegment(lat, lng, line);
      if (!segment) continue;
      const distKm = segment.distanceMeters / 1000.0;
      if (distKm < nearestKm) nearestKm = distKm;
    }

    if (nearestKm <= bufferKm) return true;
    if (nearestKm <= ZONE_AUTHORITATIVE_REACH_KM) withinReachOfAnyZone = true;
  }

  return withinReachOfAnyZone ? false : null;
}

// Matches GeofenceUtils.isWhalePositionVerified: caps the buffer at
// WHALE_POSITION_VERIFY_BUFFER_CAP_METERS first (a user-chosen/derived uncertainty can't be
// inflated to make verification easier), then checks it against real water/river data. Returns
// null exactly when isWithinGeofenceBuffer does -- out of reach of every well-sourced zone --
// so a caller should fall through to the online coastline-channel RPC fallback, not treat null
// as a rejection.
function isWhalePositionVerified(lat, lng, uncertaintyRadiusMeters) {
  const cappedBufferMeters = Math.min(uncertaintyRadiusMeters, WHALE_POSITION_VERIFY_BUFFER_CAP_METERS);
  return isWithinGeofenceBuffer(lat, lng, cappedBufferMeters);
}

// Matches GeofenceUtils.isWithinCoastlineChannelFallback: best-effort online-only upgrade for a
// point isWhalePositionVerified already returned null for (out of reach of every well-sourced
// zone) -- never called on its own. Bounded by a timeout in place of an explicit connectivity
// check, same reasoning as native: an offline device just does not get an answer in time and
// this resolves false, identical to the fallback not existing.
const COASTLINE_CHANNEL_FALLBACK_TIMEOUT_MS = 6000;

async function isWithinCoastlineChannelFallback(lat, lng) {
  const timeout = new Promise((resolve) => setTimeout(() => resolve(null), COASTLINE_CHANNEL_FALLBACK_TIMEOUT_MS));
  const result = await Promise.race([isPointWithinCoastlineChannel(lat, lng), timeout]);
  return result === true;
}
