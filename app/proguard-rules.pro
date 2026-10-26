# Launcher shrink rules (v3.12.0 shrink build).
# The launcher's own code is kept in full, so no feature can be renamed
# or removed by R8: home UI, pills, menus, phone remote, Jarvis, ADB shell.
# Only library code (RecyclerView, dadb, Kotlin stdlib, okio) is allowed to
# shrink to what is actually reachable, which is where the old 4.5 MB went.
-keep class com.kulwant.atvhome.** { *; }
# dadb is the phone-remote ADB client: keep its public entry points in full,
# R8 still drops the parts of dadb/okio that no code path reaches.
-keep class dadb.Dadb { *; }
-keep class dadb.Dadb$Companion { *; }
-keep class dadb.AdbKeyPair { *; }
-keep class dadb.AdbKeyPair$Companion { *; }
-keep class dadb.AdbStream { *; }
# Speech recognition / TTS are system APIs, but keep the Jarvis listener
# classes reachable (already covered by the launcher keep above).
-dontwarn dadb.**
-dontwarn okio.**
