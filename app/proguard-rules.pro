# AETHER keeps no reflection of its own; the libraries bring their own rules.
# Media3's session and the playback service are reached by name from the system.
-keep class app.aether.player.PlaybackService { *; }
-keep class app.aether.player.KeepService { *; }
# kotlinx.serialization: the generated serializers of the catalogue's models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.aether.** { *** Companion; }
-keepclasseswithmembers class app.aether.** { kotlinx.serialization.KSerializer serializer(...); }
