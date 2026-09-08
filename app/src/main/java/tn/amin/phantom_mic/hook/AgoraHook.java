package tn.amin.phantom_mic.hook;

import android.media.AudioFormat;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import tn.amin.phantom_mic.PhantomManager;
import tn.amin.phantom_mic.log.Logger;

public class AgoraHook {

    public static void hook(ClassLoader classLoader) {
        Logger.d("Installing Agora Engine hooks");

        hookAgoraWebRtcAudioRecord(classLoader);
        hookAgoraAudioRecord(classLoader);
        hookAgoraRtcEngine(classLoader);
    }

    private static void hookAgoraWebRtcAudioRecord(ClassLoader classLoader) {
        String[] classNames = {
                "io.agora.base.internal.voiceengine.WebRtcAudioRecord",
                "io.agora.base.internal.audio.WebRtcAudioRecord",
                "io.agora.rtc.audio.MediaCodecAudioRecord"
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz == null) continue;

                Logger.d("Found Agora audio class: " + className);

                // Hook initRecording / InitRecording
                XposedBridge.hookAllMethods(clazz, "InitRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleInitRecording(param);
                    }
                });

                XposedBridge.hookAllMethods(clazz, "initRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleInitRecording(param);
                    }
                });

                // Hook startRecording / StartRecording
                XposedBridge.hookAllMethods(clazz, "StartRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("Agora StartRecording called");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.ensureLoaded();
                        }
                    }
                });

                XposedBridge.hookAllMethods(clazz, "startRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("Agora startRecording called");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.ensureLoaded();
                        }
                    }
                });

                // Hook nativeDataIsRecorded
                XposedBridge.hookAllMethods(clazz, "nativeDataIsRecorded", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null && manager.isAudioLoaded()) {
                                Object thisObject = param.thisObject;
                                ByteBuffer byteBuffer = findByteBufferField(thisObject);
                                if (byteBuffer != null) {
                                    int bytes = 0;
                                    if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                        bytes = (int) param.args[0];
                                    } else if (param.args.length > 1 && param.args[1] instanceof Integer) {
                                        bytes = (int) param.args[1];
                                    }
                                    if (bytes <= 0) {
                                        bytes = byteBuffer.capacity();
                                    }
                                    manager.overwriteBuffer(byteBuffer, bytes);
                                }
                            }
                        } catch (Throwable t) {
                            Logger.d("Agora nativeDataIsRecorded error: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable t) {
                Logger.d("Error hooking Agora class " + className + ": " + t.getMessage());
            }
        }
    }

    private static void hookAgoraAudioRecord(ClassLoader classLoader) {
        String[] classNames = {
                "io.agora.rtc.audio.AgoraAudioRecord",
                "io.agora.rtc2.audio.AgoraAudioRecord"
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz == null) continue;

                Logger.d("Found AgoraAudioRecord: " + className);

                XposedBridge.hookAllMethods(clazz, "startRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("AgoraAudioRecord startRecording");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.ensureLoaded();
                        }
                    }
                });

                XposedBridge.hookAllMethods(clazz, "read", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null && param.getResult() instanceof Integer) {
                                int result = (int) param.getResult();
                                if (result > 0 && param.args.length > 0) {
                                    if (param.args[0] instanceof byte[]) {
                                        byte[] data = (byte[]) param.args[0];
                                        int offset = param.args.length > 1 && param.args[1] instanceof Integer ? (int) param.args[1] : 0;
                                        manager.overwriteBuffer(data, offset, result);
                                    } else if (param.args[0] instanceof ByteBuffer) {
                                        ByteBuffer buf = (ByteBuffer) param.args[0];
                                        manager.overwriteBuffer(buf, result);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Logger.d("Agora read hook error: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookAgoraRtcEngine(ClassLoader classLoader) {
        String[] rtcEngineClasses = {
                "io.agora.rtc.RtcEngine",
                "io.agora.rtc.RtcEngineImpl",
                "io.agora.rtc2.RtcEngine",
                "io.agora.rtc2.internal.RtcEngineImpl"
        };

        for (String className : rtcEngineClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz == null) continue;

                Logger.d("Found Agora RtcEngine class: " + className);

                XposedBridge.hookAllMethods(clazz, "joinChannel", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("Agora joinChannel detected -> ensuring phantom audio ready");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.load();
                        }
                    }
                });

                XposedBridge.hookAllMethods(clazz, "enableAudio", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("Agora enableAudio detected");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.load();
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void handleInitRecording(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args.length >= 2 && param.args[0] instanceof Integer && param.args[1] instanceof Integer) {
                int sampleRate = (int) param.args[0];
                int channels = (int) param.args[1];
                Logger.d("Agora initRecording: " + sampleRate + "Hz, channels=" + channels);

                PhantomManager manager = PhantomManager.getInstance();
                if (manager != null) {
                    int channelMask = (channels == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
                    manager.updateAudioFormat(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                    manager.load();
                }
            }
        } catch (Throwable t) {
            Logger.d("Agora handleInitRecording error: " + t.getMessage());
        }
    }

    private static ByteBuffer findByteBufferField(Object object) {
        if (object == null) return null;
        Class<?> clazz = object.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (ByteBuffer.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        ByteBuffer buf = (ByteBuffer) field.get(object);
                        if (buf != null) {
                            return buf;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
