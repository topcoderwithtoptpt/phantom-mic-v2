package tn.amin.phantom_mic.hook;

import android.media.AudioFormat;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import tn.amin.phantom_mic.PhantomManager;
import tn.amin.phantom_mic.log.Logger;

public class WebRtcHook {

    public static void hook(ClassLoader classLoader) {
        Logger.d("Installing WebRTC hooks");

        hookWebRtcModern(classLoader);
        hookWebRtcLegacy(classLoader);
        hookJavaAudioDeviceModule(classLoader);
    }

    private static void hookWebRtcModern(ClassLoader classLoader) {
        String[] classNames = {
                "org.webrtc.audio.WebRtcAudioRecord",
                "org.webrtc.audio.WebRtcAudioEffects",
                "org.webrtc.audio.JavaAudioDeviceModule$AudioRecordErrorCallback"
        };

        try {
            Class<?> webrtcClass = XposedHelpers.findClassIfExists("org.webrtc.audio.WebRtcAudioRecord", classLoader);
            if (webrtcClass != null) {
                Logger.d("Found modern WebRtcAudioRecord class");

                // Hook initRecording(int sampleRate, int channels)
                XposedBridge.hookAllMethods(webrtcClass, "initRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int sampleRate = (int) param.args[0];
                            int channels = (int) param.args[1];
                            Logger.d("WebRTC (Modern) initRecording: " + sampleRate + "Hz, channels=" + channels);

                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null) {
                                int channelMask = (channels == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
                                manager.updateAudioFormat(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                                manager.load();
                            }
                        } catch (Throwable t) {
                            Logger.d("WebRTC initRecording hook error: " + t.getMessage());
                        }
                    }
                });

                // Hook startRecording()
                XposedBridge.hookAllMethods(webrtcClass, "startRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("WebRTC (Modern) startRecording");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.ensureLoaded();
                        }
                    }
                });

                // Hook stopRecording()
                XposedBridge.hookAllMethods(webrtcClass, "stopRecording", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("WebRTC (Modern) stopRecording");
                    }
                });

                // Hook nativeDataIsRecorded
                XposedBridge.hookAllMethods(webrtcClass, "nativeDataIsRecorded", new XC_MethodHook() {
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
                            Logger.d("WebRTC nativeDataIsRecorded hook error: " + t.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Logger.d("Error setting up modern WebRTC hooks: " + t.getMessage());
        }
    }

    private static void hookWebRtcLegacy(ClassLoader classLoader) {
        try {
            Class<?> legacyClass = XposedHelpers.findClassIfExists("org.webrtc.voiceengine.WebRtcAudioRecord", classLoader);
            if (legacyClass != null) {
                Logger.d("Found legacy WebRtcAudioRecord class");

                // Hook InitRecording(int sampleRate, int channels)
                XposedBridge.hookAllMethods(legacyClass, "InitRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int sampleRate = (int) param.args[0];
                            int channels = (int) param.args[1];
                            Logger.d("WebRTC (Legacy) InitRecording: " + sampleRate + "Hz, channels=" + channels);

                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null) {
                                int channelMask = (channels == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
                                manager.updateAudioFormat(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                                manager.load();
                            }
                        } catch (Throwable t) {
                            Logger.d("Legacy WebRTC InitRecording error: " + t.getMessage());
                        }
                    }
                });

                // Hook StartRecording()
                XposedBridge.hookAllMethods(legacyClass, "StartRecording", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("WebRTC (Legacy) StartRecording");
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.ensureLoaded();
                        }
                    }
                });

                // Hook StopRecording()
                XposedBridge.hookAllMethods(legacyClass, "StopRecording", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Logger.d("WebRTC (Legacy) StopRecording");
                    }
                });

                // Hook nativeDataIsRecorded
                XposedBridge.hookAllMethods(legacyClass, "nativeDataIsRecorded", new XC_MethodHook() {
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
                            Logger.d("Legacy WebRTC nativeDataIsRecorded error: " + t.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Logger.d("Error setting up legacy WebRTC hooks: " + t.getMessage());
        }
    }

    private static void hookJavaAudioDeviceModule(ClassLoader classLoader) {
        try {
            Class<?> admClass = XposedHelpers.findClassIfExists("org.webrtc.audio.JavaAudioDeviceModule", classLoader);
            if (admClass != null) {
                Logger.d("Found JavaAudioDeviceModule class");
            }
        } catch (Throwable ignored) {
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
