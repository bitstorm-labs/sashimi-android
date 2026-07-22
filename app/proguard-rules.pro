# Sashimi R8 / ProGuard rules.
#
# The library dependencies (Retrofit, OkHttp, Media3, Room, WorkManager) ship
# their own consumer rules inside their AARs, so these rules focus on what R8
# can't infer on its own: kotlinx.serialization reflection over our model DTOs,
# the WorkManager worker constructor invoked reflectively, and a few dontwarns.

-keepattributes Signature, Exceptions, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes *Annotation*

# Kotlin metadata (kotlinx.serialization + reflection rely on it).
-keep class kotlin.Metadata { *; }

# ---- kotlinx.serialization (official consumer rules) ----
-dontnote kotlinx.serialization.**

# Keep the Companion of every @Serializable class.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep serializer() on the companion of every @Serializable class.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep INSTANCE + serializer() of @Serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-braces: keep our model DTOs, their generated $serializer classes, and
# every serializer() accessor in our packages.
-keep @kotlinx.serialization.Serializable class dev.bitstorm.sashimi.** { *; }
-keep,includedescriptorclasses class dev.bitstorm.sashimi.**$$serializer { *; }
-keepclassmembers class dev.bitstorm.sashimi.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- WorkManager ----
# The default WorkerFactory instantiates workers reflectively by their
# (Context, WorkerParameters) constructor — keep it so R8 doesn't strip it.
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---- dontwarn for optional / transitive references ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.serialization.**
