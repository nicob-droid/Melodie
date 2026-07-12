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

# =========================================================================
# Règles R8 / ProGuard pour Melodie
# =========================================================================

# Conserver les infos utiles aux stack traces (crash reports lisibles).
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ----- Google Mobile Ads (AdMob) -----
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**
-keep class com.google.android.gms.internal.ads.** { *; }
# Certaines classes d'AdMob sont chargées par réflexion via Dynamite.
-keep public class com.google.android.gms.common.** { *; }

# ----- Google Play Services (auth, base) -----
-keep class com.google.android.gms.common.api.** { *; }
-dontwarn com.google.android.gms.**

# ----- Room -----
# Room génère des implémentations ; on garde les entités/DAO annotés.
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# Entités / modèles de données Melodie (accédés par Room via réflexion des champs).
-keep class com.melodie.player.data.entity.** { *; }
-keep class com.melodie.player.data.model.** { *; }

# ----- Hilt / Dagger -----
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep,allowobfuscation @dagger.hilt.android.AndroidEntryPoint class *
-keep,allowobfuscation @dagger.hilt.android.HiltAndroidApp class *
-dontwarn dagger.hilt.**
-dontwarn javax.inject.**

# ----- WorkManager -----
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-dontwarn androidx.work.**

# ----- Media3 / ExoPlayer -----
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ----- Glide -----
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# ----- Google API Client / Drive (utilise réflexion + modèles JSON) -----
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.util.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.**
-dontwarn org.apache.http.**
-dontwarn com.google.common.**

# ----- OkHttp (via Media3 datasource) -----
-dontwarn okhttp3.**
-dontwarn okio.**

# ----- AndroidX / divers -----
-dontwarn org.jetbrains.annotations.**
