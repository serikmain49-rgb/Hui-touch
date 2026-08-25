# Минификация по умолчанию отключена (см. build.gradle).
# Если её включать — эти правила обязательны: Shizuku находит user service ПО ИМЕНИ КЛАССА.
-keep class com.huitouch.ShizukuTouchService { *; }
-keep class com.huitouch.IShizukuTouch { *; }
-keep class com.huitouch.IShizukuTouch$* { *; }
