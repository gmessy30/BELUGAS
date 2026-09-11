// Same project/credentials the native app uses (see local.properties at the repo root).
// The anon key is safe to ship client-side by design -- it's already embedded in every built
// APK -- access is controlled by Postgres/PostgREST grants and storage policies, not secrecy.
const SUPABASE_URL = "https://vwbcrctzsqukutvlbqwy.supabase.co";
const SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZ3YmNyY3R6c3F1a3V0dmxicXd5Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODUwMzU1MjksImV4cCI6MjEwMDYxMTUyOX0.whIuJ2U6UFdO_DXF-INwA23TAl-DBo264aQ8kEX6sJo";

const SIGHTING_PHOTOS_BUCKET = "sighting-photos";

// Matches RegionConfig.kt's Regions.COOK_INLET default center (the native app's default region).
const DEFAULT_MAP_CENTER = [60.5544, -151.2583];
const DEFAULT_MAP_ZOOM = 8;
