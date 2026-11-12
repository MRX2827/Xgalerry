# Xgalerry

Xgalerry is a local Android gallery for photos and videos by **mrx**. It uses Jetpack Compose, Material 3 Expressive, Android MediaStore and Media3. The app is available in English and Russian and supports Android 10 (API 29) and later.

The gallery animates between tabs, filters and album contents. The viewer expands from a thumbnail, follows a downward swipe, and returns to the thumbnail when it is visible. It supports horizontal paging, pinch and double-tap zoom, video seeking and replay, sharing, and on-device favorites. Use the settings sheet to select a system, light, or dark theme and optionally match the wallpaper on Android 12 and later.

## Build

Install OpenJDK 21, Android SDK Platform 37 and Build Tools 36.0.0. Set `ANDROID_HOME` to the Android SDK location and build with Gradle.

```sh
./gradlew :app:assembleGithubRelease :app:lintGithubRelease
```

The unsigned APK is generated under `app/build/outputs/apk/github/release/`. The `githubRelease` variant enables R8 and resource shrinking. To distribute an APK independently, use Android Build Tools `apksigner` with your release key. Keep that key private and use the same key for updates.

## F-Droid

Store listings and artwork are in `fastlane/metadata/android/`. The application ID is `io.github.mrx2827.xgalerry`. The same `githubRelease` variant is used for a source build. It contains no network permission, proprietary services, or bundled demo media.

The [F-Droid build recipe](fdroid/io.github.mrx2827.xgalerry.yml) points to the public repository and the `v1.0.0` tag. Follow the [F-Droid submission guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/) to propose the app for inclusion. The F-Droid build infrastructure compiles and signs the app under its normal source-build process.

## License and attribution

The Xgalerry source code is licensed under [GPL-3.0-or-later](LICENSE). The viewer spring, corner blend and dismissal thresholds in `ViewerMotion.kt` were adapted from the AMORX fork of [Rhythm](https://github.com/cromaguy/Rhythm), by Anjishnu Nandi and contributors.

The launcher and store icon were supplied by mrx. Confirm the applicable redistribution terms before submitting the icon to [F-Droid](https://f-droid.org/en/docs/Inclusion_Policy/).

The store screenshots under `fastlane/metadata/android/` show sample media created with the built-in ImageGen tool. The sample media is used for the store listing only.

> Photorealistic-natural photography contact sheet, square canvas divided into exactly 3 by 3 equal square cells, absolutely edge to edge with no margins and no borders or gutters. Nine separate authentic landscape and travel camera photographs for an Android photo gallery sample dataset. Row 1: alpine lake and fir trees with jagged mountain at sunrise, wide coastal cliffs with deep blue water, reddish desert rock formations at golden hour. Row 2: pale pink flowers and shadow detail, wet Tokyo street reflections at blue hour without readable signs, Italian lemon tree with stucco architecture. Row 3: evergreen forest with drifting fog, waves rolling onto basalt beach seen from overhead, sunlit meadow with layered blue mountains. Clean vivid but believable natural color, all distinct. No text, logos, frames, labels or watermarks. Place all hard photo cuts on exact thirds for cropping.
