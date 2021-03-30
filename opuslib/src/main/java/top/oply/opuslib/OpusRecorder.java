package top.oply.opuslib;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Created by young on 2015/7/2.
 */
public class OpusRecorder {
    private static int RECORDER_SAMPLERATE = 16000;
    private static int RECORDER_BITRATE = 16000;
    private static int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private OpusRecorder(int sampleRate, int channels, int encoding) {
        RECORDER_SAMPLERATE = sampleRate;
        RECORDER_CHANNELS = channels;
        RECORDER_AUDIO_ENCODING = encoding;
    }

    private static volatile OpusRecorder oRecorder;

    public static OpusRecorder getInstance(int sampleRate, int channels, int encoding) {
        if (oRecorder == null)
            synchronized (OpusRecorder.class) {
                if (oRecorder == null)
                    oRecorder = new OpusRecorder(sampleRate, channels, encoding);
            }
        return oRecorder;
    }

    public static OpusRecorder getInstance() {
        if (oRecorder == null)
            synchronized (OpusRecorder.class) {
                if (oRecorder == null)
                    oRecorder = new OpusRecorder(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            }
        return oRecorder;
    }

    private static final int STATE_NONE = 0;
    private static final int STATE_STARTED = 1;

    private static final String TAG = OpusRecorder.class.getName();
    private volatile int state = STATE_NONE;

    private AudioRecord recorder = null;
    private Thread recordingThread = new Thread();
    private OpusTool opusTool = new OpusTool();
    private int bufferSize = 0;
    private String filePath = null;
    private ByteBuffer fileBuffer = ByteBuffer.allocateDirect(1920);// Should be 1920, to accord with function writeFreme()
    private Utils.AudioTime mRecordTime = new Utils.AudioTime();

    class RecordThread implements Runnable {
        public void run() {
            mRecordTime.setTimeInSecond(0);

            writeAudioDataToFile();
        }
    }


    public void startRecording(final String file) {

        if (state == STATE_STARTED)
            return;

        int minBufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);
        bufferSize = (minBufferSize / 1920 + 1) * 1920;

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RECORDER_SAMPLERATE, RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING, bufferSize);

        NoiseSuppressor noiseSuppressor = null;
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(recorder.getAudioSessionId());
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            } catch (Exception e) {
                Log.e(TAG, "[initRecorder] unable to init noise suppressor: " + e);
            }
        }

        AutomaticGainControl automaticGainControl = null;
        if (AutomaticGainControl.isAvailable()) {
            try {
                automaticGainControl = AutomaticGainControl.create(recorder.getAudioSessionId());
                if (automaticGainControl != null) automaticGainControl.setEnabled(true);
            } catch (Exception e) {
                Log.e(TAG, "[initRecorder] unable to init automatic gain control: " + e);
            }
        }

        recorder.startRecording();
        state = STATE_STARTED;
        if (file.isEmpty()) {
            filePath = OpusTrackInfo.getInstance().getAValidFileName("OpusRecord");
        } else {
            filePath = file;
        }
//        filePath = file.isEmpty() ? initRecordFileName() : file;
        int rst = opusTool.startRecording(filePath, RECORDER_SAMPLERATE, RECORDER_BITRATE, 1);
        if (rst != 1) {
            Log.e(TAG, "recorder initially error");
            return;
        }

        recordingThread = new Thread(new RecordThread(), "OpusRecord Thrd");
        recordingThread.start();
    }


    private void writeAudioDataToOpus(ByteBuffer buffer, int size) {
        ByteBuffer finalBuffer = ByteBuffer.allocateDirect(size);
        finalBuffer.put(buffer);
        finalBuffer.rewind();
        boolean flush = false;

        //write data to Opus file
        while (state == STATE_STARTED && finalBuffer.hasRemaining()) {
            int oldLimit = -1;
            if (finalBuffer.remaining() > fileBuffer.remaining()) {
                oldLimit = finalBuffer.limit();
                finalBuffer.limit(fileBuffer.remaining() + finalBuffer.position());
            }
            fileBuffer.put(finalBuffer);
            if (fileBuffer.position() == fileBuffer.limit() || flush) {
                int length = !flush ? fileBuffer.limit() : finalBuffer.position();

                int rst = opusTool.writeFrame(fileBuffer, length);
                if (rst != 0) {
                    fileBuffer.rewind();
                }
            }
            if (oldLimit != -1) {
                finalBuffer.limit(oldLimit);
            }
        }
    }

    private void writeAudioDataToFile() {
        if (state != STATE_STARTED)
            return;

        ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);

        while (state == STATE_STARTED) {
            buffer.rewind();
            int len = recorder.read(buffer, bufferSize);
            Log.d(TAG, "\n lengh of buffersize is " + len);
            if (len != AudioRecord.ERROR_INVALID_OPERATION) {
                try {
                    writeAudioDataToOpus(buffer, len);
                } catch (Exception e) {
                    Utils.printE(TAG, e);
                }
            }

        }
    }

    private void updateTrackInfo() {
        OpusTrackInfo info = OpusTrackInfo.getInstance();
        info.addOpusFile(filePath);
    }

    public void stopRecording() {
        if (state != STATE_STARTED)
            return;

        state = STATE_NONE;
        try {
            Thread.sleep(200);
        } catch (Exception e) {
            Utils.printE(TAG, e);
        }

        if (null != recorder) {
            opusTool.stopRecording();
            recordingThread = null;
            recorder.stop();
            recorder.release();
            recorder = null;
        }

        updateTrackInfo();
    }

    public boolean isWorking() {
        return state != STATE_NONE;
    }

    public void release() {
        if (state != STATE_NONE) {
            stopRecording();
        }
    }
}
