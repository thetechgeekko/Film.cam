# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===== Tunable Annotation System Rules =====
# Keep the @Tunable annotation
-keep @interface com.particlesdevs.photoncamera.settings.annotations.Tunable
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations

# Keep all classes with @Tunable annotated fields
-keep class * {
    @com.particlesdevs.photoncamera.settings.annotations.Tunable <fields>;
}

# Keep field names for @Tunable annotated fields (needed for reflection)
-keepclassmembers class * {
    @com.particlesdevs.photoncamera.settings.annotations.Tunable <fields>;
}

# Keep the Tunable system classes
-keep class com.particlesdevs.photoncamera.settings.annotations.** { *; }
-keep class com.particlesdevs.photoncamera.settings.TunableInjector { *; }
-keep class com.particlesdevs.photoncamera.settings.TunablePreferenceGenerator { *; }
-keep class com.particlesdevs.photoncamera.settings.TunablePreferenceGenerator$* { *; }
-keep class com.particlesdevs.photoncamera.settings.TunableSettingsManager { *; }
-keep class com.particlesdevs.photoncamera.settings.SettingsManagerExtensions { *; }
-keep class com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference { *; }

# Keep all postpipeline nodes with @Tunable fields for reflection
-keepclassmembers class com.particlesdevs.photoncamera.processing.opengl.postpipeline.** {
    @com.particlesdevs.photoncamera.settings.annotations.Tunable <fields>;
}
-keep class org.chickenhook.restrictionbypass.*{
    public *;
}

# Missing class rules
-dontwarn org.apache.commons.lang.functor.Predicate

# ===== Film.cam Rules =====
# Keep Film.cam GPU pipeline classes
-keep class com.filmcam.gpu.** { *; }
-keep class com.filmcam.pipeline.** { *; }
-keep class com.filmcam.capture.** { *; }
-keep class com.filmcam.model.** { *; }
-keep class com.filmcam.utils.** { *; }
-keep class com.filmcam.ui.** { *; }
-keep class com.filmcam.settings.** { *; }

# Preserve shader strings and EGL bindings
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.opengl.GLSurfaceView.* <methods>;
}

# Keep FilmSettings data classes for JSON serialization
-keep class com.filmcam.model.FilmSettings { *; }
-keep class com.filmcam.model.FilmEmulation { *; }
-keep class com.filmcam.model.EmulationParameters { *; }
-keep class com.filmcam.model.DefaultParameters { *; }
-keep class com.filmcam.model.ToneCurve { *; }
-keep class com.filmcam.model.BorderSettings { *; }

# Keep enum classes
-keepclassmembers enum com.filmcam.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Kotlinx Serialization for preset JSON import/export
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class com.filmcam.model.**$$serializer { *; }
-keep class com.filmcam.model.**Companion { *; }
-keepclasseswithmembers class com.filmcam.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Film.cam Activity
-keep class com.filmcam.CameraActivity { *; }
-keep class com.filmcam.HapticPattern { *; }
