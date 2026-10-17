# React Native release rules, plus the native cutout stack.

-keep class ai.onnxruntime.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.caverock.androidsvg.**

-keep class app.erase.background.bridge.** { *; }
-keepclassmembers class * {
    @com.facebook.react.bridge.ReactMethod *;
}

-keep @dagger.hilt.EntryPoint interface * { *; }
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**
