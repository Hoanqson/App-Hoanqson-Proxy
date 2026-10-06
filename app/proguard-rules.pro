# Data & Model classes parsed by Gson / JSON
-keepclassmembers class app.hoanqson.proxy.model.** { *; }
-keep class app.hoanqson.proxy.model.** { *; }
-keepclassmembers class app.hoanqson.proxy.database.** { *; }
-keep class app.hoanqson.proxy.database.** { *; }

# VPN Service & Tun2Socks native JNI bridge
-keep class app.hoanqson.proxy.vpn.** { *; }
-keep class engine.** { *; }

# OkHttp & Gson reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers enum * { *; }