package tn.amin.phantom_mic;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

import tn.amin.phantom_mic.audio.AudioMaster;
import tn.amin.phantom_mic.hook.ActivityResultWrapper;
import tn.amin.phantom_mic.log.Logger;

public class PhantomManager {
    private static final String DEFAULT_RECORDINGS_PATH = "Recordings";
    private static final String FILE_CONFIG = "phantom.txt";
    private static final int REQUEST_CODE = 2608;

    private static volatile PhantomManager sInstance;

    private Uri mUriPath;
    private final WeakReference<Context> mContext;

    private final AudioMaster mAudioMaster;
    private final SPManager mSPManager;
    private final FileManager mFileManager;
    private boolean mNeedPrepare = true;
    private boolean mNativeHookDone = false;

    public static PhantomManager getInstance() {
        return sInstance;
    }

    public PhantomManager(Context context, boolean isNativeHook) {
        Logger.d("Init PhantomManager");
        sInstance = this;

        mContext = new WeakReference<>(context != null ? context.getApplicationContext() : null);
        mAudioMaster = new AudioMaster();
        mSPManager = new SPManager(context != null ? context : null);
        mFileManager = new FileManager(context != null ? context : null);

        if (isNativeHook) {
            try {
                nativeHook();
                mNativeHookDone = true;
                Logger.d("Native hook initialized successfully");
            } catch (Throwable t) {
                Logger.d("Native hook failed: " + t.getMessage());
            }
        }
    }

    public void interceptIntent(Intent intent) {
    }

    public void forceUriPath() {
        ensureHasUriPath();
    }

    public void prepare(Activity activity) {
        if (mUriPath != null) {
            return;
        }

        mNeedPrepare = false;
        if (mSPManager != null && mSPManager.getUriPath() == null) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, getDefaultUriPath());
            }

            ActivityResultWrapper arWrapper = new ActivityResultWrapper(activity, REQUEST_CODE);
            Context ctx = getContext();
            if (ctx != null) {
                Toast.makeText(ctx, "PhantomMic: Choose recordings folder", Toast.LENGTH_LONG).show();
            }
            arWrapper.start(intent, (resultCode, resultData) -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (resultData != null && resultData.getData() != null) {
                        Uri uri = resultData.getData();
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        try {
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        } catch (Exception ignored) {
                        }

                        if (mSPManager != null) {
                            mSPManager.setUriPath(uri);
                        }
                        mUriPath = uri;

                        Logger.d("Saved recordings uri: " + mUriPath);
                        load();
                    }
                }
            });
        } else if (mSPManager != null) {
            mUriPath = mSPManager.getUriPath();
        }
        Logger.d("PhantomManager.prepare done");
    }

    public Uri getDefaultUriPath() {
        File defaultPath = new File(Environment.getExternalStorageDirectory(), DEFAULT_RECORDINGS_PATH);
        return Uri.fromFile(defaultPath);
    }

    public void updateAudioFormat(int sampleRate, int channelMask, int encoding) {
        mAudioMaster.setFormat(sampleRate, channelMask, encoding);
        Logger.d("Target format updated: " + sampleRate + "Hz, mask " + channelMask + ", encoding " + encoding);
    }

    public synchronized void load() {
        ensureHasUriPath();

        if (mUriPath == null) {
            Logger.d("Cannot load audio: mUriPath is null");
            return;
        }

        String fileName = mFileManager.readLine(mUriPath, FILE_CONFIG);
        if (fileName == null || fileName.trim().isEmpty()) {
            Logger.d("No audio file specified in " + FILE_CONFIG + ", using real microphone");
            return;
        }

        FileDescriptor fd = mFileManager.openAudioWithName(mUriPath, fileName.trim());

        if (fd == null) {
            Logger.d("Could not open audio file: " + fileName.trim());
            return;
        }

        mAudioMaster.load(fd);
        Logger.d("Audio file load initiated: " + fileName.trim());
    }

    public void ensureLoaded() {
        if (!mAudioMaster.isLoaded()) {
            load();
        }
    }

    private void ensureHasUriPath() {
        if (mUriPath == null && mSPManager != null) {
            mUriPath = mSPManager.getUriPath();
        }
        if (mUriPath == null) {
            Context ctx = getContext();
            if (ctx != null) {
                File extFiles = ctx.getExternalFilesDir(null);
                if (extFiles != null) {
                    mUriPath = Uri.fromFile(new File(extFiles, DEFAULT_RECORDINGS_PATH));
                }
            }
            if (mUriPath == null) {
                mUriPath = getDefaultUriPath();
            }
            Logger.d("Defaulting recordings URI to " + mUriPath);
        }
    }

    public void unload() {
        mAudioMaster.unload();
        mFileManager.close();
        Logger.d("Done unloading audio data");
    }

    public boolean isAudioLoaded() {
        return mAudioMaster.isLoaded();
    }

    public boolean overwriteBuffer(byte[] buffer, int offset, int length) {
        ensureLoaded();
        return mAudioMaster.overwriteBuffer(buffer, offset, length);
    }

    public boolean overwriteBuffer(short[] buffer, int offset, int length) {
        ensureLoaded();
        return mAudioMaster.overwriteBuffer(buffer, offset, length);
    }

    public boolean overwriteBuffer(ByteBuffer buffer, int length) {
        ensureLoaded();
        return mAudioMaster.overwriteBuffer(buffer, length);
    }

    public boolean overwriteBuffer(float[] buffer, int offset, int length) {
        ensureLoaded();
        return mAudioMaster.overwriteBuffer(buffer, offset, length);
    }

    public void onAudioRecordInit(AudioRecord record, int sampleRate, int channelConfig, int audioFormat) {
        Logger.d("AudioRecord initialized: rate=" + sampleRate + ", channel=" + channelConfig + ", format=" + audioFormat);
        updateAudioFormat(sampleRate, channelConfig, audioFormat);
        load();
    }

    public void onAudioRecordStart(AudioRecord record) {
        if (record != null) {
            int rate = record.getSampleRate();
            int channelConfig = record.getChannelConfiguration();
            int format = record.getAudioFormat();
            Logger.d("AudioRecord started: rate=" + rate + ", channel=" + channelConfig + ", format=" + format);
            updateAudioFormat(rate, channelConfig, format);
        }
        load();
    }

    public void onAudioRecordStop(AudioRecord record) {
        Logger.d("AudioRecord stopped");
    }

    private Context getContext() {
        return mContext.get();
    }

    private ContentResolver getContentResolver() {
        Context ctx = getContext();
        return ctx != null ? ctx.getContentResolver() : null;
    }

    public boolean needPrepare() {
        return mNeedPrepare;
    }

    private native void nativeHook();
}
