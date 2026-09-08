package tn.amin.phantom_mic.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.nailik.androidresampler.Resampler;
import io.github.nailik.androidresampler.ResamplerConfiguration;
import io.github.nailik.androidresampler.data.ResamplerChannel;
import io.github.nailik.androidresampler.data.ResamplerQuality;
import tn.amin.phantom_mic.log.Logger;

public class AudioMaster {
    private static final int TIMEOUT_MS = 1000;

    private volatile AudioFormat mOutFormat;
    private volatile boolean mIsLoading = false;
    private volatile boolean mIsLoaded = false;

    private final Object mBufferLock = new Object();
    private byte[] mPcmData = null;
    private int mReadPosition = 0;

    private FileDescriptor mLastFd = null;

    private final ExecutorService audioLoadExecutor = Executors.newSingleThreadExecutor();

    public AudioMaster() {
        // Default to standard 48000Hz, Mono, 16-bit PCM for VoIP/WebRTC/Agora
        mOutFormat = new AudioFormat.Builder()
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
    }

    public void load(FileDescriptor fd) {
        if (fd == null) {
            Logger.d("FileDescriptor is null, cannot load audio");
            return;
        }

        mLastFd = fd;

        if (mIsLoading) {
            Logger.d("AudioMaster is already loading another audio, aborting previous load");
            mIsLoading = false;
        }

        mIsLoading = true;
        mIsLoaded = false;

        audioLoadExecutor.execute(() -> {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(fd);
                int audioTrackIndex = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat trackFormat = extractor.getTrackFormat(i);
                    String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        break;
                    }
                }

                if (audioTrackIndex == -1) {
                    audioTrackIndex = 0;
                }

                extractor.selectTrack(audioTrackIndex);
                MediaFormat format = extractor.getTrackFormat(audioTrackIndex);

                String mimeType = format.getString(MediaFormat.KEY_MIME);
                if (mimeType == null) {
                    Logger.d("mimeType cannot be null");
                    mIsLoading = false;
                    return;
                }

                MediaCodec codec = MediaCodec.createDecoderByType(mimeType);
                codec.configure(format, null, null, 0);
                codec.start();

                loadData(codec, format, extractor);
            } catch (Exception e) {
                Logger.d("Error loading audio file: " + e.getMessage());
                mIsLoading = false;
            } finally {
                try {
                    extractor.release();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void unload() {
        mIsLoading = false;
        mIsLoaded = false;
        synchronized (mBufferLock) {
            mPcmData = null;
            mReadPosition = 0;
        }
        mLastFd = null;
    }

    private void loadData(MediaCodec codec, MediaFormat format, MediaExtractor extractor) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        ByteArrayOutputStream pcmAccumulator = new ByteArrayOutputStream();

        boolean isEOS = false;
        try {
            do {
                if (!isEOS) {
                    int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_MS);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isEOS = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_MS);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] pcmData = new byte[bufferInfo.size];
                        outputBuffer.get(pcmData);
                        outputBuffer.clear();

                        // Resample and store PCM data
                        byte[] resampledChunk = processInBuffer(format, pcmData);
                        if (resampledChunk != null && resampledChunk.length > 0) {
                            pcmAccumulator.write(resampledChunk);
                            try {
                                onBufferChunkLoaded(resampledChunk);
                            } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    format = codec.getOutputFormat();
                    Logger.d("Codec output format changed to " + format);
                }

                if (!mIsLoading) {
                    Logger.d("Loading aborted");
                    break;
                }
            } while ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0);
        } catch (Exception e) {
            Logger.d("Exception during audio decode: " + e.getMessage());
        } finally {
            try {
                codec.stop();
                codec.release();
            } catch (Exception ignored) {
            }
        }

        byte[] finalPcm = pcmAccumulator.toByteArray();
        if (finalPcm.length > 0) {
            synchronized (mBufferLock) {
                mPcmData = finalPcm;
                mReadPosition = 0;
                mIsLoaded = true;
            }
            Logger.d("AudioMaster: loaded " + finalPcm.length + " bytes of PCM audio");
        } else {
            Logger.d("AudioMaster: decoded 0 bytes of PCM audio");
        }

        mIsLoading = false;
        try {
            onLoadDone();
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
        }
    }

    private byte[] processInBuffer(MediaFormat source, byte[] bufferChunk) {
        if (bufferChunk == null || bufferChunk.length == 0) {
            return null;
        }

        if (mOutFormat == null) {
            mOutFormat = new AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
        }

        int srcChannels = 1;
        if (source.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            srcChannels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        }

        int srcSampleRate = 44100;
        if (source.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            srcSampleRate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        }

        ResamplerChannel inChannel = (srcChannels == 1) ? ResamplerChannel.MONO : ResamplerChannel.STEREO;
        ResamplerChannel outChannel = (mOutFormat.getChannelCount() == 1) ? ResamplerChannel.MONO : ResamplerChannel.STEREO;

        int targetSampleRate = mOutFormat.getSampleRate();
        if (targetSampleRate <= 0) {
            targetSampleRate = 48000;
        }

        if (srcSampleRate == targetSampleRate && inChannel == outChannel) {
            return bufferChunk;
        }

        try {
            ResamplerConfiguration configuration = new ResamplerConfiguration(
                    ResamplerQuality.BEST,
                    inChannel,
                    srcSampleRate,
                    outChannel,
                    targetSampleRate
            );
            Resampler resampler = new Resampler(configuration);
            return resampler.resample(bufferChunk);
        } catch (Exception e) {
            Logger.d("Resampling error: " + e.getMessage());
            return bufferChunk;
        }
    }

    public void setFormat(AudioFormat format) {
        if (format == null) return;
        mOutFormat = format;
    }

    public void setFormat(int sampleRate, int channelMask, int encoding) {
        int validSampleRate = sampleRate > 0 ? sampleRate : 48000;
        int validEncoding = encoding > 0 ? encoding : AudioFormat.ENCODING_PCM_16BIT;
        int channelCount = (channelMask == AudioFormat.CHANNEL_IN_STEREO || channelMask == 12 || channelMask == 2) ? 2 : 1;
        int mask = (channelCount == 2) ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;

        if (mOutFormat != null &&
                mOutFormat.getSampleRate() == validSampleRate &&
                mOutFormat.getChannelMask() == mask &&
                mOutFormat.getEncoding() == validEncoding) {
            return;
        }

        mOutFormat = new AudioFormat.Builder()
                .setSampleRate(validSampleRate)
                .setChannelMask(mask)
                .setEncoding(validEncoding)
                .build();

        Logger.d("Target format updated: " + validSampleRate + "Hz, mask " + mask + ", encoding " + validEncoding);

        if (mLastFd != null && mIsLoaded) {
            load(mLastFd);
        }
    }

    public AudioFormat getFormat() {
        if (mOutFormat == null) {
            mOutFormat = new AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
        }
        return mOutFormat;
    }

    public boolean isLoaded() {
        return mIsLoaded && mPcmData != null && mPcmData.length > 0;
    }

    public boolean overwriteBuffer(byte[] dst, int offset, int length) {
        if (!mIsLoaded || mPcmData == null || mPcmData.length == 0 || dst == null || length <= 0) {
            return false;
        }

        synchronized (mBufferLock) {
            if (mPcmData == null || mPcmData.length == 0) return false;
            int pcmLen = mPcmData.length;
            int toCopy = length;
            int destOffset = offset;

            while (toCopy > 0) {
                int available = pcmLen - mReadPosition;
                int chunk = Math.min(toCopy, available);
                System.arraycopy(mPcmData, mReadPosition, dst, destOffset, chunk);
                destOffset += chunk;
                toCopy -= chunk;
                mReadPosition = (mReadPosition + chunk) % pcmLen;
            }
            return true;
        }
    }

    public boolean overwriteBuffer(short[] dst, int offset, int length) {
        if (!mIsLoaded || mPcmData == null || mPcmData.length == 0 || dst == null || length <= 0) {
            return false;
        }

        int bytesNeeded = length * 2;
        byte[] tempBytes = new byte[bytesNeeded];
        if (overwriteBuffer(tempBytes, 0, bytesNeeded)) {
            ByteBuffer bb = ByteBuffer.wrap(tempBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < length; i++) {
                dst[offset + i] = bb.getShort();
            }
            return true;
        }
        return false;
    }

    public boolean overwriteBuffer(ByteBuffer dst, int length) {
        if (!mIsLoaded || mPcmData == null || mPcmData.length == 0 || dst == null || length <= 0) {
            return false;
        }

        int actualLength = Math.min(length, dst.remaining());
        if (actualLength <= 0) return false;

        byte[] tempBytes = new byte[actualLength];
        if (overwriteBuffer(tempBytes, 0, actualLength)) {
            int pos = dst.position();
            if (dst.isDirect()) {
                dst.put(tempBytes, 0, actualLength);
                dst.position(pos + actualLength);
            } else if (dst.hasArray()) {
                byte[] array = dst.array();
                int arrayOffset = dst.arrayOffset() + pos;
                System.arraycopy(tempBytes, 0, array, arrayOffset, actualLength);
                dst.position(pos + actualLength);
            } else {
                dst.put(tempBytes, 0, actualLength);
            }
            return true;
        }
        return false;
    }

    public boolean overwriteBuffer(float[] dst, int offset, int length) {
        if (!mIsLoaded || mPcmData == null || mPcmData.length == 0 || dst == null || length <= 0) {
            return false;
        }

        short[] tempShorts = new short[length];
        if (overwriteBuffer(tempShorts, 0, length)) {
            for (int i = 0; i < length; i++) {
                dst[offset + i] = tempShorts[i] / 32768.0f;
            }
            return true;
        }
        return false;
    }

    public native void onBufferChunkLoaded(byte[] bufferChunk);

    public native void onLoadDone();
}
