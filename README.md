This is a Kotlin Multiplatform project targeting Android, iOS.

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Configuration

The Supabase URL and anon key are not committed to source. Copy `local.properties.sample`
into `local.properties` (already gitignored) and fill in `supabase.url` / `supabase.anonKey`,
or set the `SUPABASE_URL` / `SUPABASE_ANON_KEY` environment variables (used in CI). These are
read at build time by `shared/build.gradle.kts` and generated into a Kotlin source file that
`SupabaseClient.kt` consumes.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

### License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](./LICENSE) --
free for non-commercial use.

The beluga artwork in `webapp/img/` and this app's Compose drawable resources is
© Luna Montgomery. All rights reserved. This artwork is not licensed under the terms above.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…