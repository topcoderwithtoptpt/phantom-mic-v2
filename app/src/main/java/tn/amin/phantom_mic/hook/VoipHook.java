package tn.amin.phantom_mic.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import tn.amin.phantom_mic.PhantomManager;
import tn.amin.phantom_mic.log.Logger;

public class VoipHook {

    public static void hook(ClassLoader classLoader, String packageName) {
        Logger.d("Installing VoIP & Video Calling hooks for package: " + packageName);

        hookTelegramVoip(classLoader);
        hookWhatsAppVoip(classLoader);
        hookDiscordVoip(classLoader);
        hookZoomVoip(classLoader);
        hookMessengerVoip(classLoader);
        hookGenericVoipEngines(classLoader);
    }

    private static void hookTelegramVoip(ClassLoader classLoader) {
        try {
            Class<?> voipService = XposedHelpers.findClassIfExists("org.telegram.messenger.voip.VoIPService", classLoader);
            if (voipService != null) {
                Logger.d("Found Telegram VoIPService");
                XposedBridge.hookAllMethods(voipService, "startAudio", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("Telegram VoIP startAudio");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.load();
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookWhatsAppVoip(ClassLoader classLoader) {
        try {
            Class<?> voipClass = XposedHelpers.findClassIfExists("com.whatsapp.voipcalling.VoIP", classLoader);
            if (voipClass != null) {
                Logger.d("Found WhatsApp VoIP class");
                XposedBridge.hookAllMethods(voipClass, "startCall", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("WhatsApp VoIP startCall");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.load();
                        }
                    }
                });
                XposedBridge.hookAllMethods(voipClass, "acceptCall", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("WhatsApp VoIP acceptCall");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.load();
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookDiscordVoip(ClassLoader classLoader) {
        try {
            Class<?> discordRtc = XposedHelpers.findClassIfExists("com.hammerandchisel.discord.common.rtc.DiscordRtc", classLoader);
            if (discordRtc != null) {
                Logger.d("Found Discord RTC class");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookZoomVoip(ClassLoader classLoader) {
        try {
            Class<?> zoomAudio = XposedHelpers.findClassIfExists("com.zipow.videobox.confapp.ConfUI", classLoader);
            if (zoomAudio != null) {
                Logger.d("Found Zoom ConfUI class");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookMessengerVoip(ClassLoader classLoader) {
        try {
            Class<?> fbRtc = XposedHelpers.findClassIfExists("com.facebook.rtc.fbwebrtc.WebrtcEngine", classLoader);
            if (fbRtc != null) {
                Logger.d("Found Facebook WebRTC Engine class");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookGenericVoipEngines(ClassLoader classLoader) {
        String[] genericClasses = {
                "org.linphone.core.LinphoneCore",
                "com.zoiper.zdk.AudioDevice",
                "com.skype.android.audio.AudioDeviceManager",
                "com.imo.android.imoim.av.AVService"
        };

        for (String className : genericClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz != null) {
                    Logger.d("Found VoIP engine class: " + className);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
