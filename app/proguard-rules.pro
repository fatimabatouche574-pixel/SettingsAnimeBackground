-keep class com.local.settingsanimebackground.hook.SettingsHook {
    public <init>();
    public void handleLoadPackage(...);
}
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
