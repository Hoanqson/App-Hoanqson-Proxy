# Data & Model classes parsed by Gson / JSON
-keepclassmembers class vn.homeproxy.keyrotator.model.** { *; }
-keep class vn.homeproxy.keyrotator.model.** { *; }
-keepclassmembers class vn.homeproxy.keyrotator.database.** { *; }
-keep class vn.homeproxy.keyrotator.database.** { *; }

# VPN Service & Tun2Socks native JNI bridge
-keep class vn.homeproxy.keyrotator.vpn.** { *; }
-keep class engine.** { *; }

# OkHttp & Gson reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers enum * { *; }