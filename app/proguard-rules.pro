-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.leatherbound.listening.data.remote.** { *** Companion; *; }
-keep,includedescriptorclasses class com.leatherbound.listening.**$$serializer { *; }
# Backups, update checks and other @Serializable classes outside data.remote.
-keepclassmembers @kotlinx.serialization.Serializable class com.leatherbound.listening.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
