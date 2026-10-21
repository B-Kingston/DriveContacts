# Release rules. Everything that native code or reflection reaches must stay as it is.
# The libraries also ship consumer rules; these broad rules are a second line of safety.

# Proton Drive SDK (Kotlin binding). Native code calls its classes and methods through JNI by name.
-keep class proton.drive.sdk.** { *; }
-keep class me.proton.drive.sdk.** { *; }

# ProtonCore (network, domain, util). It uses reflection and kotlinx.serialization.
-keep class me.proton.** { *; }
-dontwarn me.proton.**

# gopenpgp and gomobile runtime (`go.Seq`, `go.Universe`). Native code looks up these classes and
# their native methods by name.
-keep class com.proton.gopenpgp.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Protobuf lite of the Drive SDK messages. The runtime reads the fields by reflection.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# JNA, when a library brings it in. It loads its own classes and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# Native code in the Drive SDK needs these coroutine members (the same rule as in the SDK).
-keep class kotlinx.coroutines.JobCancellationException
-keepclassmembers class kotlinx.coroutines.** {
    void cancel(...);
}

# kotlinx.serialization: the generated serializers of the app classes.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class dev.drivecontacts.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
    static ** $serializer;
}
-keep,includedescriptorclasses class dev.drivecontacts.**$$serializer { *; }
-keepclassmembers class dev.drivecontacts.** {
    *** Companion;
}
-keepclasseswithmembers class dev.drivecontacts.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit 2.9.0 (pulled in by ProtonCore network) predates its own R8 full-mode rules. Without them
# R8 drops the generic signatures of these types, and every suspend API call fails with
# "Class cannot be cast to ParameterizedType". These are the rules later Retrofit versions ship.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
-dontwarn retrofit2.**
