package com.wifispeaker;

import android.content.Context;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Volume compartilhado entre receptor e transmissor por uma conexão de controle (TCP, porta
 * CONTROL_PORT), separada do áudio. Mensagens de texto, uma por linha: "VOL n", n de 0 a 100.
 *
 * O volume vale no receptor (é onde o som toca). O slider do transmissor é um controle remoto que
 * espelha o do receptor: mexer em um muda o outro. Ao conectar, o receptor manda o valor atual.
 */
final class Volume {
    private Volume() {}

    /** Volume de reprodução deste aparelho quando ele é o receptor (0 a 100). */
    static volatile int receiver = 100;

    /** Volume do receptor conectado, como este aparelho (transmissor) o conhece (0 a 100). */
    static volatile int remote = 100;

    /** Conexão de controle do lado receptor (vinda de um transmissor) e do lado transmissor. */
    private static volatile Socket receiverSide;
    private static volatile Socket senderSide;

    private interface IntCallback {
        void on(int value);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "wifispeaker-control-out");
            t.setDaemon(true);
            return t;
        }
    });

    // ------------------------------------------------------------ chamadas da tela

    /** O usuário mexeu no slider do receptor, neste aparelho. */
    static void setReceiverFromUser(int v) {
        receiver = clamp(v);
        send(receiverSide, receiver);
    }

    /** O usuário mexeu no slider do receptor remoto, no transmissor. */
    static void setRemoteFromUser(int v) {
        remote = clamp(v);
        send(senderSide, remote);
    }

    // ------------------------------------------------------------ lado receptor

    /** Servidor de controle: aceita o transmissor e atende o volume. */
    static final class Server extends Thread {
        private final Context ctx;
        private volatile ServerSocket server;
        private volatile boolean stop = false;

        Server(Context ctx) {
            super("wifispeaker-control-server");
            this.ctx = ctx.getApplicationContext();
        }

        @Override
        public void run() {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(Proto.CONTROL_PORT));
                server = ss;
                while (!stop) {
                    final Socket c = ss.accept();
                    c.setTcpNoDelay(true);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serveAsReceiver(ctx, c);
                        }
                    }, "wifispeaker-control-peer").start();
                }
            } catch (IOException ignored) {
                // porta em uso ou serviço parando: o áudio funciona, só o volume não sincroniza
            }
        }

        void shutdown() {
            stop = true;
            close(server);
            close(receiverSide);
        }
    }

    private static void serveAsReceiver(final Context ctx, Socket s) {
        Socket old = receiverSide;
        receiverSide = s; // um transmissor novo assume
        close(old);
        try {
            write(s, receiver); // o transmissor passa a mostrar o volume atual
            readLoop(s, new IntCallback() {
                @Override
                public void on(int v) {
                    receiver = v;
                    Prefs.sp(ctx).edit().putInt("volume", v).apply();
                }
            });
        } catch (IOException ignored) {
        } finally {
            close(s);
            if (receiverSide == s) {
                receiverSide = null;
            }
        }
    }

    // ------------------------------------------------------------ lado transmissor

    /** Abre o controle com o receptor (melhor esforço: um receptor antigo simplesmente não responde). */
    static void startSenderSide(final String host) {
        closeSenderSide();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(host, Proto.CONTROL_PORT), 2000);
                    s.setTcpNoDelay(true);
                    senderSide = s;
                    readLoop(s, new IntCallback() {
                        @Override
                        public void on(int v) {
                            remote = v;
                        }
                    });
                } catch (IOException ignored) {
                } finally {
                    close(s);
                    if (senderSide == s) {
                        senderSide = null;
                    }
                }
            }
        }, "wifispeaker-control-sender").start();
    }

    static void closeSenderSide() {
        close(senderSide);
        senderSide = null;
    }

    // ------------------------------------------------------------ utilidades

    private static void readLoop(Socket s, IntCallback cb) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("VOL ")) {
                try {
                    cb.on(clamp(Integer.parseInt(line.substring(4).trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /** Envio em segundo plano (a tela não pode usar a rede). */
    private static void send(final Socket s, final int v) {
        if (s == null) {
            return;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    write(s, v);
                } catch (IOException ignored) {
                }
            }
        });
    }

    private static void write(Socket s, int v) throws IOException {
        byte[] b = ("VOL " + clamp(v) + "\n").getBytes("US-ASCII");
        synchronized (s) {
            s.getOutputStream().write(b);
            s.getOutputStream().flush();
        }
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    private static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
