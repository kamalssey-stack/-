# ProGuard rules for CarTune Pro
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.cartune.pro.CarTuneBridge { *; }
-keep class com.hoho.android.usbserial.** { *; }
