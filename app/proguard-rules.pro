# Keep OkHttp/okio and Gson model classes used for the Iris wire protocol.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class de.letheapp.iris.** { *; }
