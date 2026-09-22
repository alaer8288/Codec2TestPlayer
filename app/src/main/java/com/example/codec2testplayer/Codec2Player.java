package com.example.codec2testplayer;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

/*
 * Minimal MediaExtractor -> MediaCodec -> Surface test player.
 *
 * Important:
 *  - No audio.
 *  - No ExoPlayer/Media3.
 *  - Input PTS is passed to MediaCodec, but is NOT used by this class
 *    to schedule playback or intentionally drop frames.
 *  - Output is released immediately to the Surface.
 *
 * Current sample file: /sdcard/Download/test.mp4
 */
public class Codec2Player {
    private static final String TAG = "Codec2TestPlayer";
    private static final String FILE = "/sdcard/Download/test.mp4";

    private final Context context;
    private final Surface surface;
    private volatile boolean running;
    private Thread thread;

    private String codecName = "unknown";
    private long inputFrames, outputFrames, droppedInput, loops;
    private long firstOutputMs = -1, lastOutputMs = -1;

    public Codec2Player(Context c, Surface s) {
        context = c;
        surface = s;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::decodeLoop, "Codec2DecodeThread");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
        }
    }

    private void decodeLoop() {
        while (running) {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            try {
                extractor.setDataSource(FILE);
                int track = selectVideoTrack(extractor);
                if (track < 0) throw new IllegalStateException("No video track: " + FILE);
                extractor.selectTrack(track);

                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                Log.i(TAG, "format=" + format);

                codec = MediaCodec.createDecoderByType(mime);
                codecName = codec.getName();
                Log.i(TAG, "decoder=" + codecName + " mime=" + mime);
                codec.configure(format, surface, null, 0);
                codec.start();

                boolean inputDone = false;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                while (running) {
                    if (!inputDone) {
                        int in = codec.dequeueInputBuffer(10000);
                        if (in >= 0) {
                            ByteBuffer buf = codec.getInputBuffer(in);
                            if (buf == null) continue;
                            buf.clear();

                            int size = extractor.readSampleData(buf, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(in, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(in, 0, size, pts, 0);
                                inputFrames++;
                                extractor.advance();
                            }
                        }
                    }

                    int out = codec.dequeueOutputBuffer(info, 10000);
                    if (out >= 0) {
                        // No PTS-based scheduling and no late-frame dropping here.
                        codec.releaseOutputBuffer(out, true);
                        outputFrames++;
                        long now = SystemClock.elapsedRealtime();
                        if (firstOutputMs < 0) firstOutputMs = now;
                        lastOutputMs = now;

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            loops++;
                            break;
                        }
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "outputFormat=" + codec.getOutputFormat());
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "decode failed", e);
                break;
            } finally {
                if (codec != null) {
                    try { codec.stop(); } catch (Throwable ignored) {}
                    try { codec.release(); } catch (Throwable ignored) {}
                }
                extractor.release();
            }
        }
    }

    private int selectVideoTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    public String getStats() {
        long elapsed = (firstOutputMs >= 0 && lastOutputMs > firstOutputMs)
                ? lastOutputMs - firstOutputMs : 0;
        double fps = elapsed > 0 ? outputFrames * 1000.0 / elapsed : 0.0;
        return "Codec2 Test Player\n" +
                "Decoder: " + codecName + "\n" +
                "Input: " + inputFrames + "\n" +
                "Output: " + outputFrames + "\n" +
                "Dropped input: " + droppedInput + "\n" +
                "Loops: " + loops + "\n" +
                String.format(java.util.Locale.US, "Output FPS: %.2f", fps);
    }
}
