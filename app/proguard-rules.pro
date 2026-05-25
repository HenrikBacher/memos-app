# Project-specific R8/ProGuard rules.
#
# All dependencies in use (kotlinx.serialization, Ktor, OkHttp, Tink, Room,
# Coil) ship consumer-proguard rules in their artifacts, so no manual rules
# are needed today. Add rules here if you introduce a dep that uses
# reflection and doesn't supply its own.
