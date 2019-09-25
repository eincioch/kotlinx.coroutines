# When editing this file, update the following files as well:
# - META-INF/com.android.tools/r8-upto-1.6.0/coroutines.pro
# - META-INF/proguard/coroutines.pro

-keep class kotlinx.coroutines.android.AndroidDispatcherFactory {*;}

-assumenosideeffects class kotlinx.coroutines.internal.FastServiceLoader {
    boolean ANDROID_DETECTED return true;
}