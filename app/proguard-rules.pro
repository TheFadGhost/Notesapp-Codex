# Notesapp ProGuard rules (release minification is disabled in M0).
# kotlinx.serialization keeps generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
