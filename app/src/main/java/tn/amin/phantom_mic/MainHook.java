package tn.amin.phantom_mic;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.PersistableBundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tn.amin.phantom_mic.hook.AgoraHook;
import tn.amin.phantom_mic.hook.AudioRecordHook;
import tn.amin.phantom_mic.hook.ClassLoaderHook;
import tn.amin.phantom_mic.hook.VoipHook;
import tn.amin.phantom_mic.hook.WebRtcHook;
import tn.amin.phantom_mic.log.Logger;

public class MainHook implements IXposedHookLoadPackage {
    private PhantomManager phantomManager = null;
    private boolean needHook = true;
    private String packageName;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (needHook) {
            needHook = false;

            packageName = lpparam.packageName;
            try {
                System.loadLibrary("xposedlab");
                Logger.d("Native xposedlab library loaded successfully");
            } catch (Throwable t) {
                Logger.d("Could not load native xposedlab library: " + t.getMessage());
            }

            Logger.d("Beginning PhantomMic hooks for " + packageName);
            doHook(lpparam);
            Logger.d("Successful PhantomMic hooks installed");
        }
    }

    private void doHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // Universal AudioRecord hook (covers all standard Java audio recording)
        AudioRecordHook.hook(lpparam.classLoader);

        // WebRTC hooks (Google Meet, Discord, Telegram, WhatsApp calls, WebRTC apps)
        WebRtcHook.hook(lpparam.classLoader);

        // Agora Engine hooks (Agora RTC, gaming voice, live video/audio call apps)
        AgoraHook.hook(lpparam.classLoader);

        // Dedicated VoIP and video calling application hooks
        VoipHook.hook(lpparam.classLoader, packageName);

        // Dynamic ClassLoader interceptor for late-loaded RTC / WebRTC / Agora SDKs
        ClassLoaderHook.hook(lpparam.classLoader, packageName);

        // Hook MediaRecorder
        try {
            XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader, "start", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Logger.d("MediaRecorder start");
                    if (phantomManager != null) {
                        phantomManager.ensureLoaded();
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // Hook Activity lifecycle to obtain Activity context & user storage permissions
        XposedHelpers.findAndHookMethod(Activity.class, "performCreate", Bundle.class, PersistableBundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                if (phantomManager != null) {
                    phantomManager.interceptIntent(activity.getIntent());
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                onActivityObtained(activity);
            }
        });

        // Hook Application onCreate to initialize PhantomManager as early as possible
        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application application = (Application) param.args[0];
                if (phantomManager == null) {
                    initPhantomManager(application.getApplicationContext());
                }
            }
        });
    }

    private void onActivityObtained(Activity activity) {
        if (phantomManager == null) {
            initPhantomManager(activity.getApplicationContext());
        }

        if (phantomManager.needPrepare()) {
            phantomManager.prepare(activity);
        }
    }

    private void initPhantomManager(Context context) {
        phantomManager = new PhantomManager(context, isNativeHook());
        if (isSpecialCase()) {
            phantomManager.forceUriPath();
        }
    }

    private boolean isSpecialCase() {
        return packageName != null && (
                packageName.equals("com.whatsapp")
                || packageName.equals("com.whatsapp.w4b")
                || packageName.equals("org.telegram.messenger")
                || packageName.equals("org.telegram.messenger.web")
                || packageName.equals("com.discord")
                || packageName.equals("com.hammerandchisel.discord")
                || packageName.equals("com.google.android.apps.tachyon")
                || packageName.equals("com.google.android.apps.meetings")
                || packageName.equals("com.facebook.orca")
                || packageName.equals("com.facebook.katana")
                || packageName.equals("com.instagram.android")
                || packageName.equals("com.android.soundrecorder")
        );
    }

    public boolean isNativeHook() {
        return true;
    }
}
