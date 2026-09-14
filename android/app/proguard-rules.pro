# STAB-01 — Release R8 baseline
#
# Keep this file targeted. Do not blanket-keep com.plugpdv.pdv.** because
# that would defeat release obfuscation and shrinking.

# Runtime metadata used by Retrofit/Gson/Kotlin and callback-heavy SDKs.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

# Preserve Parcelable creators used through the Android framework.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Several API DTOs are serialized/deserialized by Gson. Preserve DTO field
# names while allowing the DTO classes and the rest of the application code
# to be optimized/obfuscated.
-keepclassmembers class com.plugpdv.pdv.models.** {
    *** *;
}

# Dspread POS / PIN / printer SDKs are vendor binaries and may use reflection,
# manifest-instantiated components or JNI. Keep their public/runtime surface
# until a vendor-provided consumer-rules contract is available and validated.
-keep class com.dspread.** { *; }
-keep interface com.dspread.** { *; }
-dontwarn com.dspread.**

# Dspread printer package also exposes classes through com.action.*.
-keep class com.action.** { *; }
-keep interface com.action.** { *; }
-dontwarn com.action.**

# Kozen/Urovo financial/printer SDK surface used by the hardware HAL.
-keep class com.pos.sdk.** { *; }
-keep interface com.pos.sdk.** { *; }
-dontwarn com.pos.sdk.**

# Sunmi service bindings rely on vendor Binder/runtime components. Keep the
# vendor namespace while still allowing the Plug PDV application itself to be
# obfuscated.
-keep class com.sunmi.** { *; }
-keep interface com.sunmi.** { *; }
-dontwarn com.sunmi.**

# SLF4J 1.x probes this optional implementation class at runtime and falls
# back when no logging binding is packaged. Do not add a logging backend just
# to satisfy R8; suppress only this known optional binding reference.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Do not add broad -ignorewarnings here. If R8 reports another optional vendor
# dependency, add the narrowest rule only after reviewing missing_rules.txt.
