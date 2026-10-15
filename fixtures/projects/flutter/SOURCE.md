`android/` holds the files that `flutter create` renders from the Flutter 3.47.7 templates
(`packages/flutter_tools/templates/app/android.tmpl` and `android-kotlin.tmpl`, fetched raw at
the tag `3.47.7`), with the template placeholders filled by the defaults in
`packages/flutter_tools/lib/src/android/gradle_utils.dart` at the same tag
(`templateAndroidGradlePluginVersion` 9.1.0, `templateKotlinGradlePluginVersion` 2.4.0,
`templateDefaultGradleVersion` 9.3.1). `pubspec.lock` holds the `sdks` block of the lockfile in
`flutter/gallery` (`flutter: ">=3.16.0"`) and one package; `pubspec.yaml` is written by hand
with the same package. Nothing was built.
