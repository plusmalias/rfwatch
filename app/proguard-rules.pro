# SQLCipher JNI classes must not be stripped/renamed.
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# Keep Room-generated code.
-keep class androidx.room.** { *; }

# kotlinx.serialization keeps generated serializers for @Serializable classes.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.rfwatch.core.rules.** { *; }
