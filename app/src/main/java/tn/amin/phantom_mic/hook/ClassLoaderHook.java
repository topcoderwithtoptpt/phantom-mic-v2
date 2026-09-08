package tn.amin.phantom_mic.hook;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import tn.amin.phantom_mic.log.Logger;

public class ClassLoaderHook {
    private static final Set<String> sHookedClasses = new HashSet<>();

    public static void hook(ClassLoader classLoader, String packageName) {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String className = (String) param.args[0];
                    Class<?> clazz = (Class<?>) param.getResult();
                    if (clazz == null || className == null) return;

                    dispatchClassHook(className, clazz.getClassLoader(), packageName);
                }
            });
        } catch (Throwable t) {
            Logger.d("Failed to hook ClassLoader.loadClass(String, boolean): " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String className = (String) param.args[0];
                    Class<?> clazz = (Class<?>) param.getResult();
                    if (clazz == null || className == null) return;

                    dispatchClassHook(className, clazz.getClassLoader(), packageName);
                }
            });
        } catch (Throwable t) {
            Logger.d("Failed to hook ClassLoader.loadClass(String): " + t.getMessage());
        }
    }

    private static synchronized void dispatchClassHook(String className, ClassLoader classLoader, String packageName) {
        if (sHookedClasses.contains(className)) return;

        if (className.startsWith("org.webrtc.")) {
            sHookedClasses.add(className);
            Logger.d("Dynamic load of WebRTC class detected: " + className);
            WebRtcHook.hook(classLoader);
        } else if (className.startsWith("io.agora.")) {
            sHookedClasses.add(className);
            Logger.d("Dynamic load of Agora class detected: " + className);
            AgoraHook.hook(classLoader);
        } else if (className.startsWith("com.whatsapp.voipcalling.") ||
                className.startsWith("org.telegram.messenger.voip.") ||
                className.startsWith("com.facebook.rtc.") ||
                className.startsWith("com.hammerandchisel.discord.") ||
                className.startsWith("us.zoom.")) {
            sHookedClasses.add(className);
            Logger.d("Dynamic load of VoIP class detected: " + className);
            VoipHook.hook(classLoader, packageName);
        }
    }
}
