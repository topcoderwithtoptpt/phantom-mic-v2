package tn.amin.phantom_mic.hook;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaSyncEvent;
import android.os.Build;

import java.nio.ByteBuffer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import tn.amin.phantom_mic.PhantomManager;
import tn.amin.phantom_mic.log.Logger;

public class AudioRecordHook {

    public static void hook(ClassLoader classLoader) {
        Logger.d("Installing AudioRecord hooks");

        try {
            Class<?> audioRecordClass = XposedHelpers.findClass("android.media.AudioRecord", classLoader);

            // Hook constructors
            XposedBridge.hookAllConstructors(audioRecordClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        AudioRecord record = (AudioRecord) param.thisObject;
                        int rate = record.getSampleRate();
                        int channelConfig = record.getChannelConfiguration();
                        int format = record.getAudioFormat();

                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.onAudioRecordInit(record, rate, channelConfig, format);
                        }
                    } catch (Throwable t) {
                        Logger.d("AudioRecord constructor hook error: " + t.getMessage());
                    }
                }
            });

            // Hook startRecording()
            XposedHelpers.findAndHookMethod(audioRecordClass, "startRecording", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        AudioRecord record = (AudioRecord) param.thisObject;
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.onAudioRecordStart(record);
                        }
                    } catch (Throwable t) {
                        Logger.d("AudioRecord startRecording hook error: " + t.getMessage());
                    }
                }
            });

            // Hook startRecording(MediaSyncEvent)
            try {
                XposedHelpers.findAndHookMethod(audioRecordClass, "startRecording", MediaSyncEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            AudioRecord record = (AudioRecord) param.thisObject;
                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null) {
                                manager.onAudioRecordStart(record);
                            }
                        } catch (Throwable t) {
                            Logger.d("AudioRecord startRecording(MediaSyncEvent) hook error: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            // Hook stop()
            XposedHelpers.findAndHookMethod(audioRecordClass, "stop", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        AudioRecord record = (AudioRecord) param.thisObject;
                        PhantomManager manager = PhantomManager.getInstance();
                        if (manager != null) {
                            manager.onAudioRecordStop(record);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            // Hook read(byte[] audioData, int offsetInBytes, int sizeInBytes)
            XposedHelpers.findAndHookMethod(audioRecordClass, "read", byte[].class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        int result = (int) param.getResult();
                        if (result > 0) {
                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null) {
                                byte[] audioData = (byte[]) param.args[0];
                                int offset = (int) param.args[1];
                                manager.overwriteBuffer(audioData, offset, result);
                            }
                        }
                    } catch (Throwable t) {
                        Logger.d("AudioRecord.read(byte[]) hook error: " + t.getMessage());
                    }
                }
            });

            // Hook read(byte[] audioData, int offsetInBytes, int sizeInBytes, int readMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    XposedHelpers.findAndHookMethod(audioRecordClass, "read", byte[].class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int result = (int) param.getResult();
                                if (result > 0) {
                                    PhantomManager manager = PhantomManager.getInstance();
                                    if (manager != null) {
                                        byte[] audioData = (byte[]) param.args[0];
                                        int offset = (int) param.args[1];
                                        manager.overwriteBuffer(audioData, offset, result);
                                    }
                                }
                            } catch (Throwable t) {
                                Logger.d("AudioRecord.read(byte[], mode) hook error: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            // Hook read(short[] audioData, int offsetInBytes, int sizeInBytes)
            XposedHelpers.findAndHookMethod(audioRecordClass, "read", short[].class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        int result = (int) param.getResult();
                        if (result > 0) {
                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null) {
                                short[] audioData = (short[]) param.args[0];
                                int offset = (int) param.args[1];
                                manager.overwriteBuffer(audioData, offset, result);
                            }
                        }
                    } catch (Throwable t) {
                        Logger.d("AudioRecord.read(short[]) hook error: " + t.getMessage());
                    }
                }
            });

            // Hook read(short[] audioData, int offsetInBytes, int sizeInBytes, int readMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    XposedHelpers.findAndHookMethod(audioRecordClass, "read", short[].class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int result = (int) param.getResult();
                                if (result > 0) {
                                    PhantomManager manager = PhantomManager.getInstance();
                                    if (manager != null) {
                                        short[] audioData = (short[]) param.args[0];
                                        int offset = (int) param.args[1];
                                        manager.overwriteBuffer(audioData, offset, result);
                                    }
                                }
                            } catch (Throwable t) {
                                Logger.d("AudioRecord.read(short[], mode) hook error: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            // Hook read(ByteBuffer audioBuffer, int sizeInBytes) - Critical for WebRTC and VoIP
            XposedHelpers.findAndHookMethod(audioRecordClass, "read", ByteBuffer.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        int result = (int) param.getResult();
                        if (result > 0) {
                            PhantomManager manager = PhantomManager.getInstance();
                            if (manager != null) {
                                ByteBuffer audioBuffer = (ByteBuffer) param.args[0];
                                manager.overwriteBuffer(audioBuffer, result);
                            }
                        }
                    } catch (Throwable t) {
                        Logger.d("AudioRecord.read(ByteBuffer) hook error: " + t.getMessage());
                    }
                }
            });

            // Hook read(ByteBuffer audioBuffer, int sizeInBytes, int readMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    XposedHelpers.findAndHookMethod(audioRecordClass, "read", ByteBuffer.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int result = (int) param.getResult();
                                if (result > 0) {
                                    PhantomManager manager = PhantomManager.getInstance();
                                    if (manager != null) {
                                        ByteBuffer audioBuffer = (ByteBuffer) param.args[0];
                                        manager.overwriteBuffer(audioBuffer, result);
                                    }
                                }
                            } catch (Throwable t) {
                                Logger.d("AudioRecord.read(ByteBuffer, mode) hook error: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            // Hook read(float[] audioData, int offsetInBytes, int sizeInBytes, int readMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    XposedHelpers.findAndHookMethod(audioRecordClass, "read", float[].class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int result = (int) param.getResult();
                                if (result > 0) {
                                    PhantomManager manager = PhantomManager.getInstance();
                                    if (manager != null) {
                                        float[] audioData = (float[]) param.args[0];
                                        int offset = (int) param.args[1];
                                        manager.overwriteBuffer(audioData, offset, result);
                                    }
                                }
                            } catch (Throwable t) {
                                Logger.d("AudioRecord.read(float[]) hook error: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            Logger.d("AudioRecord hooks installed successfully");
        } catch (Throwable t) {
            Logger.d("Failed to hook AudioRecord: " + t.getMessage());
        }
    }
}
