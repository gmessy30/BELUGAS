// Firebase Web Push config -- loaded both as a normal <script> (main thread, index.html) and via
// importScripts() from sw.js (service-worker thread), so it's plain classic-script `const`
// declarations, not an ES module -- a shared value both contexts can read the same way.
//
// NOT the same values as androidApp's google-services.json: a Firebase project has a SEPARATE
// per-platform "app" registration (Android app / iOS app / Web app), each with its own apiKey/
// appId even though they share one projectId/messagingSenderId. Get these from:
//   Firebase Console -> Project Settings (gear icon) -> General tab -> "Your apps" ->
//   Web app (</> icon -- register one first if none exists yet) -> the firebaseConfig object
//   shown there.
//
// ↓↓↓ REPLACE EVERY VALUE BELOW -- these placeholders will not work as committed. ↓↓↓
const FIREBASE_CONFIG = {
  apiKey: "REPLACE_WITH_FIREBASE_WEB_API_KEY",
  authDomain: "REPLACE_WITH_FIREBASE_AUTH_DOMAIN",
  projectId: "REPLACE_WITH_FIREBASE_PROJECT_ID",
  storageBucket: "REPLACE_WITH_FIREBASE_STORAGE_BUCKET",
  messagingSenderId: "REPLACE_WITH_FIREBASE_MESSAGING_SENDER_ID",
  appId: "REPLACE_WITH_FIREBASE_WEB_APP_ID"
};

// VAPID PUBLIC key (not the same as the FIREBASE_SERVICE_ACCOUNT_JSON secret the notify-new-
// sighting edge function uses -- that's a private server credential; this is the public half of
// a separate Web Push key pair, safe to ship in client code same as the config object above):
//   Firebase Console -> Project Settings -> Cloud Messaging tab -> Web Push certificates ->
//   "Key pair" (generate one if none exists yet).
const FCM_VAPID_PUBLIC_KEY = "REPLACE_WITH_VAPID_PUBLIC_KEY";
