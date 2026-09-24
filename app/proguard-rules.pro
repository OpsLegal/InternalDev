# kotlinx.serialization keeps generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.opslegal.tda.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.opslegal.tda.**$$serializer { *; }
