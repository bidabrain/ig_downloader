# Methods called from injected JavaScript via AndroidBridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
