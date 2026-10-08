# kotlinx.serialization: keep generated serializers for our @Serializable classes
# (API responses, saved queue, lyrics and mixes are all (de)serialized by name).
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class im.flume.hearth.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class im.flume.hearth.**$$serializer { *; }
