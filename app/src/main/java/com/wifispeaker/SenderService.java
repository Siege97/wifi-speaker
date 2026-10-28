package com.wifispeaker;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
 * Transmissor: captura o áudio dos apps (Android 10+) e/ou do microfone e envia por TCP
 * ao receptor. Se a conexão cair, reconecta sozinho. Pode achar o receptor automaticamente.
 */
public class SenderService extends Service {

    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_AUTO = "auto";
    static final String EXTRA_APPS = "apps";
    static final String EXTRA_MIC = "mic";

    private static final int NOTIF_ID = 2;

    private volatile boolean running = false;
    private Thread thread;
    private volatile Socket socket;
    private String lastName;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final boolean apps = intent.getBooleanExtra(EXTRA_APPS, false);
        final boolean mic = intent.getBooleanExtra(EXTRA_MIC, false);
        final boolean auto = intent.getBooleanExtra(EXTRA_AUTO, true);
        final String host = intent.getStringExtra(EXTRA_HOST);
        final int code = intent.getIntExtra(EXTRA_CODE, 0);
        final Intent data = intent.getParcelableExtra(EXTRA_DATA);

        if (!apps && !mic) {
            Status.sender = "Erro: escolha ao menos uma fonte de áudio";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (apps && Build.VERSION.SDK_INT < 29) {
            Status.sender = "Erro: o som dos apps exige Android 10 ou mais recente";
            stopSelf();
            return START_NOT_STICKY;
        }

        // Tipos do serviço em primeiro plano (o Android 14+ confere).
        // No Android 14+ a projeção só pode ser obtida depois de entrar em primeiro plano.
        int type = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            if (apps) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            if (mic && Build.VERSION.SDK_INT >= 30) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
        }
        Notif.startForeground(this, NOTIF_ID, Notif.build(this, "Transmitindo áudio"), type);

        if (thread != null) {
            return START_NOT_STICKY;
        }

        lastName = Prefs.sp(this).getString("last_receiver", null);
        running = true;
        Status.senderRunning = true;
        thread = new Thread(() -> transmit(host, auto, apps, mic, code, data), "wifispeaker-sender");
        thread.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
        Status.senderRunning = false;
        if (!Status.sender.startsWith("Erro")) {
            Status.sender = "Parado";
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- transmissão

    private void transmit(String host, boolean auto, boolean apps, boolean mic,
                          int code, Intent data) {
        MediaProjection projection = null;
        AudioRecord appRec = null;
        AudioRecord micRec = null;
        try {
            if (apps) {
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

                appRec = createAppRecord(projection);
                if (appRec.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("não foi possível iniciar a captura dos apps");
                }
            }
            if (mic) {
                micRec = createMicRecord();
                if (micRec.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("não foi possível abrir o microfone");
                }
            }

            // Só microfone: mono. Com som dos apps: estéreo (o microfone é duplicado nos dois lados).
            final int channels = apps ? 2 : 1;
            final int chunk = Proto.CHUNK;
            byte[] appBuf = apps ? new byte[chunk] : null;
            byte[] micBuf = mic ? new byte[apps ? chunk / 2 : chunk] : null;
            byte[] mixed = (apps && mic) ? new byte[chunk] : null;

            while (running) {
                Socket s = connect(host, auto);
                if (s == null) {
                    break;
                }
                try {
                    // (Re)inicia a captura a cada conexão: descarta o áudio acumulado enquanto
                    // procurava o receptor ou durante uma queda.
                    restartAll(appRec, micRec);

                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    out.writeInt(Proto.MAGIC);
                    out.writeInt(Proto.SAMPLE_RATE);
                    out.writeInt(channels);
                    out.flush();
                    Status.sender = "Transmitindo para " + s.getInetAddress().getHostAddress()
                            + describe(apps, mic);

                    while (running) {
                        if (apps) {
                            readFully(appRec, appBuf, appBuf.length);
                        }
                        if (mic) {
                            readFully(micRec, micBuf, micBuf.length);
                        }
                        byte[] toSend;
                        if (apps && mic) {
                            mix(appBuf, micBuf, mixed);
                            toSend = mixed;
                        } else {
                            toSend = apps ? appBuf : micBuf;
                        }
                        out.write(toSend, 0, toSend.length);
                    }
                } catch (IOException e) {
                    if (running) {
                        Status.sender = "Conexão perdida, reconectando...";
                    }
                } finally {
                    closeQuietly(s);
                    socket = null;
                }
                if (running && !pause(1000)) {
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                Status.sender = "Erro: " + e.getMessage();
            }
        } finally {
            running = false;
            release(appRec);
            release(micRec);
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

    /** Conecta ao receptor, procurando-o na rede se estiver em modo automático. */
    private Socket connect(String host, boolean auto) {
        while (running) {
            String target = host;
            int port = Proto.PORT;

            if (auto) {
                Status.sender = "Procurando receptor na rede...";
                Discovery.Found f = Discovery.find(lastName, 1500);
                if (f == null) {
                    continue;
                }
                target = f.host;
                port = f.port;
                lastName = f.name;
                Prefs.sp(this).edit().putString("last_receiver", f.name).apply();
            }

            Status.sender = "Conectando a " + target + "...";
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(target, port), 4000);
                s.setTcpNoDelay(true);
                socket = s;
                return s;
            } catch (IOException e) {
                Status.sender = "Sem conexão com " + target + ", tentando de novo...";
                if (!pause(2000)) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static String describe(boolean apps, boolean mic) {
        if (apps && mic) {
            return " (apps + microfone)";
        }
        return apps ? " (apps)" : " (microfone)";
    }

    // ---------------------------------------------------------------- captura

    private AudioRecord createAppRecord(MediaProjection projection) {
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

    private AudioRecord createMicRecord() {
        int min = AudioRecord.getMinBufferSize(Proto.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(min * 2, Proto.CHUNK * 4);
        return new AudioRecord(MediaRecorder.AudioSource.MIC, Proto.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
    }

    private void restartAll(AudioRecord a, AudioRecord b) {
        for (AudioRecord r : new AudioRecord[]{a, b}) {
            if (r != null) {
                try {
                    r.stop();
                } catch (Exception ignored) {
                }
                r.startRecording();
            }
        }
    }

    private void release(AudioRecord r) {
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            r.release();
        }
    }

    /** Lê exatamente len bytes (o microfone pode devolver menos de uma vez). */
    private void readFully(AudioRecord rec, byte[] buf, int len) {
        int off = 0;
        while (off < len && running) {
            int n = rec.read(buf, off, len - off);
            if (n < 0) {
                throw new IllegalStateException("erro ao ler o áudio (" + n + ")");
            }
            off += n;
        }
    }

    /** Soma o microfone (mono) ao som dos apps (estéreo), com proteção contra estouro. */
    private static void mix(byte[] app, byte[] mic, byte[] out) {
        int frames = out.length / 4;
        for (int i = 0; i < frames; i++) {
            int a = i * 4;
            int m = i * 2;
            int pl = (short) ((app[a] & 0xff) | (app[a + 1] << 8));
            int pr = (short) ((app[a + 2] & 0xff) | (app[a + 3] << 8));
            int mm = (short) ((mic[m] & 0xff) | (mic[m + 1] << 8));
            int l = clamp(pl + mm);
            int r = clamp(pr + mm);
            out[a] = (byte) l;
            out[a + 1] = (byte) (l >> 8);
            out[a + 2] = (byte) r;
            out[a + 3] = (byte) (r >> 8);
        }
    }

    private static int clamp(int v) {
        return v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
