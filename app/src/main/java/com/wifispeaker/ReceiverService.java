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
 * Funciona a partir do Android 5.0.
 */
public class ReceiverService extends Service {

    private static final int NOTIF_ID = 1;

    private volatile boolean running = false;
    private volatile ServerSocket server;
    private volatile Socket current;
    private Thread thread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

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
            thread = new Thread(this::loop, "wifispeaker-receiver");
            thread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
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
            Socket s = null;
            try {
                s = server.accept();
                current = s;
                s.setTcpNoDelay(true);
                Status.receiver = "Conectado a " + s.getInetAddress().getHostAddress();
                play(s);
            } catch (IOException e) {
                // conexão encerrada ou erro de rede: volta a esperar
            } finally {
                closeQuietly(s);
                current = null;
            }
            if (running) {
                Status.receiver = "Aguardando o transmissor...";
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
        // Buffer um pouco maior que o mínimo: troca um pouco de atraso por menos falhas no som.
        int bufSize = Math.max(min * 2, Proto.CHUNK * 4);

        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask,
                AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
        try {
            track.play();
            byte[] buf = new byte[Proto.CHUNK];
            while (running) {
                in.readFully(buf);
                track.write(buf, 0, buf.length);
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
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
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
