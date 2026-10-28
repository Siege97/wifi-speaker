package com.wifispeaker;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Transmissor: captura o áudio que está tocando no celular (Android 10+)
 * e envia por TCP para o receptor.
 */
public class SenderService extends Service {

    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_HOST = "host";

    private static final int NOTIF_ID = 2;

    private volatile boolean running = false;
    private Thread thread;
    private volatile Socket socket;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT < 29 || intent == null) {
            Status.sender = "Este modo exige Android 10 ou mais recente";
            stopSelf();
            return START_NOT_STICKY;
        }

        // No Android 14+ o serviço precisa estar em primeiro plano ANTES de obter a projeção.
        Notif.startForeground(this, NOTIF_ID,
                Notif.build(this, "Transmitindo o áudio do celular"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (thread != null) {
            return START_NOT_STICKY;
        }

        final String host = intent.getStringExtra(EXTRA_HOST);
        final int code = intent.getIntExtra(EXTRA_CODE, 0);
        final Intent data = intent.getParcelableExtra(EXTRA_DATA);

        running = true;
        Status.senderRunning = true;
        thread = new Thread(() -> transmit(host, code, data), "wifispeaker-sender");
        thread.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        Status.senderRunning = false;
        if (!Status.sender.startsWith("Erro")) {
            Status.sender = "Parado";
        }
        super.onDestroy();
    }

    private void transmit(String host, int code, Intent data) {
        MediaProjection projection = null;
        AudioRecord record = null;
        Socket s = null;
        try {
            if (host == null || host.trim().isEmpty() || data == null) {
                throw new IOException("dados de conexão ausentes");
            }

            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) {
                throw new IOException("a captura de áudio não foi autorizada");
            }
            // O Android 14+ exige um callback registrado antes de usar a projeção.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    running = false;
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            Status.sender = "Conectando a " + host + "...";
            s = new Socket();
            s.connect(new InetSocketAddress(host.trim(), Proto.PORT), 5000);
            s.setTcpNoDelay(true);
            socket = s;

            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(Proto.MAGIC);
            out.writeInt(Proto.SAMPLE_RATE);
            out.writeInt(Proto.CHANNELS);
            out.flush();

            record = createRecord(projection);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("não foi possível iniciar a captura de áudio");
            }
            record.startRecording();
            Status.sender = "Transmitindo para " + host;

            byte[] buf = new byte[Proto.CHUNK];
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n < 0) {
                    throw new IOException("erro ao ler o áudio (" + n + ")");
                }
                n -= n % 4; // mantém o alinhamento de frames estéreo 16 bits
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            if (running) {
                Status.sender = "Erro: " + e.getMessage();
            }
        } finally {
            running = false;
            if (record != null) {
                try {
                    record.stop();
                } catch (Exception ignored) {
                }
                record.release();
            }
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            if (projection != null) {
                try {
                    projection.stop();
                } catch (Exception ignored) {
                }
            }
            Status.senderRunning = false;
            stopSelf();
        }
    }

    private AudioRecord createRecord(MediaProjection projection) {
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        int min = AudioRecord.getMinBufferSize(Proto.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(min * 2, Proto.CHUNK * 4);

        return new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();
    }
}
