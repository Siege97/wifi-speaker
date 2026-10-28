package com.wifispeaker;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Receptor: fica escutando na porta TCP, recebe PCM do transmissor e toca
 * pela saída de áudio do aparelho (alto-falante, fone ou cabo P2).
 * Também responde à descoberta automática dos transmissores.
 * Funciona a partir do Android 5.0.
 */
public class ReceiverService extends Service {

    private static final int NOTIF_ID = 1;

    private volatile boolean running = false;
    private volatile ServerSocket server;
    private volatile Socket current;
    private Thread thread;
    private Discovery.Responder responder;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notif.startForeground(this, NOTIF_ID,
                Notif.build(this, "Receptor ativo, aguardando o transmissor"),
                Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);

        if (thread == null) {
            running = true;
            Status.receiverRunning = true;
            acquireLocks();

            responder = new Discovery.Responder(Build.MANUFACTURER + " " + Build.MODEL);
            responder.start();

            thread = new Thread(this::loop, "wifispeaker-receiver");
            thread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (responder != null) {
            responder.shutdown();
        }
        closeQuietly(server);
        closeQuietly(current);
        releaseLocks();
        Status.receiverRunning = false;
        if (!Status.receiver.startsWith("Erro")) {
            Status.receiver = "Parado";
        }
        super.onDestroy();
    }

    private void loop() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(Proto.PORT));
            server = ss;
        } catch (IOException e) {
            Status.receiver = "Erro ao abrir a porta " + Proto.PORT + ": " + e.getMessage();
            Status.receiverRunning = false;
            stopSelf();
            return;
        }

        Status.receiver = "Aguardando o transmissor...";
        while (running) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running || server.isClosed()) {
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;
                }
                continue;
            }

            // Um transmissor novo sempre assume. Isso é o que permite reconectar depois de uma
            // queda do Wi-Fi: a conexão antiga pode ficar "meio aberta" no receptor, sem nunca
            // dar erro, e ela travaria a fila se só aceitássemos uma conexão por vez.
            Socket old = current;
            current = s;
            closeQuietly(old);
            new Thread(() -> handle(s), "wifispeaker-play").start();
        }
    }

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            Status.receiver = "Conectado a " + s.getInetAddress().getHostAddress();
            play(s);
        } catch (IOException e) {
            // conexão encerrada ou erro de rede
        } finally {
            closeQuietly(s);
            if (current == s) {
                current = null;
                if (running) {
                    Status.receiver = "Aguardando o transmissor...";
                }
            }
        }
    }

    private void play(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 16384));

        if (in.readInt() != Proto.MAGIC) {
            throw new IOException("protocolo inválido");
        }
        int rate = in.readInt();
        int channels = in.readInt();
        if (rate < 8000 || rate > 192000 || (channels != 1 && channels != 2)) {
            throw new IOException("formato de áudio inválido");
        }

        int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int frameBytes = channels * 2;

        // bufferMs == 0: padrão (toca na hora). Caso contrário, acumula esse tanto de áudio
        // antes de começar a tocar: mais estável contra falhas do Wi-Fi, porém com mais atraso.
        int bufferMs = Prefs.bufferMs(this);
        int target = 0;
        int bufSize;
        if (bufferMs <= 0) {
            bufSize = Math.max(min * 2, Proto.CHUNK * 4);
        } else {
            target = (int) ((long) rate * frameBytes * bufferMs / 1000);
            target -= target % frameBytes;
            bufSize = Math.max(min, target * 2 + Proto.CHUNK * 2);
        }

        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask,
                AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
        try {
            boolean playing = false;
            if (bufferMs <= 0) {
                track.play();
                playing = true;
            }
            int queued = 0;
            byte[] buf = new byte[Proto.CHUNK];
            while (running) {
                in.readFully(buf);
                track.write(buf, 0, buf.length);
                if (!playing) {
                    queued += buf.length;
                    if (queued >= target) {
                        track.play();
                        playing = true;
                    }
                }
            }
        } finally {
            try {
                track.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifispeaker:receiver");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "wifispeaker:receiver");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();

            // Sem isso, alguns aparelhos descartam os pacotes de broadcast da descoberta.
            multicastLock = wm.createMulticastLock("wifispeaker:discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
