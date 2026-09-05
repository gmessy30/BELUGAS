-- Frozen snapshot of sightingEntity's CREATE TABLE exactly as it stood the moment migration
-- tracking started (schema version 1, before 1.sqm existed) -- captured verbatim from
-- `git show c984898^:shared/src/commonMain/sqldelight/com/cookinlet/belugas/db/SightingEntity.sq`.
-- Read by verifySightingEntityMigration (see shared/build.gradle.kts) as the starting point for
-- the "apply every .sqm in order" path, the same role a checked-in schema-version-1.db snapshot
-- would play for SQLDelight's own (unused here, see that task's comment) verify machinery.
--
-- NEVER EDIT THIS FILE. It is a historical fact, not a schema to maintain -- every future
-- change to sightingEntity's shape belongs in a new numbered .sqm file applied on top of this,
-- exactly like 1.sqm was. Editing this file to "match" a schema change defeats the entire point
-- of the migration check: it would make the migrated and fresh paths agree by construction
-- instead of by an actual matching migration existing.
CREATE TABLE sightingEntity (
    id TEXT NOT NULL PRIMARY KEY,
    timestamp INTEGER NOT NULL,
    lat REAL NOT NULL,
    lng REAL NOT NULL,
    heading TEXT NOT NULL,
    countWhites INTEGER NOT NULL DEFAULT 0,
    countGreys INTEGER NOT NULL DEFAULT 0,
    countCalves INTEGER NOT NULL DEFAULT 0,
    countUnknown INTEGER NOT NULL DEFAULT 0,
    photoUrl TEXT,
    isSynced INTEGER NOT NULL DEFAULT 0,
    observedAt INTEGER NOT NULL DEFAULT 0,
    observerType TEXT NOT NULL DEFAULT 'SELF',
    whaleLat REAL,
    whaleLng REAL,
    uncertaintyRadiusMeters REAL,
    uncertaintyBucket TEXT,
    travelBearingDegrees REAL,
    travelBearingSource TEXT,
    positionSource TEXT
);
