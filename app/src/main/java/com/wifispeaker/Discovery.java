package com.wifispeaker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Descoberta de receptores na rede local por broadcast UDP. */
final class Discovery {
    private Discovery() {}

    static final class Found {
        final String host;
        final int port;
        final String name;

        Found(String host, int port, String name) {
            this.host = host;
            this.port = port;
            this.name = name;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ------------------------------------------------------------ cliente (transmissor)

    /** Procura receptores durante até timeoutMs. Para cedo se achar o preferido. */
    static List<Found> probe(int timeoutMs, String preferred) {
        List<Found> found = new ArrayList<>();
        Set<String> own = localAddresses();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(150);

            byte[] pkt = new byte[5];
            putInt(pkt, 0, Proto.DISC_MAGIC);
            pkt[4] = 1;

            List<InetAddress> targets = broadcastTargets();
            long start = System.currentTimeMillis();
            long end = start + timeoutMs;
            int sent = 0;
            byte[] rb = new byte[256];

            while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted()) {
                if (sent < 3 && System.currentTimeMillis() - start >= sent * 500L) {
                    for (InetAddress t : targets) {
                        try {
                            sock.send(new DatagramPacket(pkt, pkt.length, t, Proto.DISCOVERY_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                    sent++;
                }
                try {
                    DatagramPacket p = new DatagramPacket(rb, rb.length);
                    sock.receive(p);
                    Found f = parse(p, own);
                    if (f != null && !contains(found, f.host)) {
                        found.add(f);
                        if (preferred != null && preferred.equals(f.name)) {
                            break;
                        }
                        // dá mais um instante para outros receptores responderem
                        end = Math.min(end, System.currentTimeMillis() + 300);
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (sock != null) {
                sock.close();
            }
        }
        return found;
    }

    /** Escolhe o receptor de nome preferido, ou o primeiro encontrado. */
    static Found find(String preferred, int timeoutMs) {
        List<Found> list = probe(timeoutMs, preferred);
        if (list.isEmpty()) {
            return null;
        }
        if (preferred != null) {
            for (Found f : list) {
                if (preferred.equals(f.name)) {
                    return f;
                }
            }
        }
        return list.get(0);
    }

    private static boolean contains(List<Found> list, String host) {
        for (Found f : list) {
            if (f.host.equals(host)) {
                return true;
            }
        }
        return false;
    }

    private static Found parse(DatagramPacket p, Set<String> own) {
        byte[] b = p.getData();
        int len = p.getLength();
        if (len < 7 || getInt(b, 0) != Proto.DISC_MAGIC || b[4] != 2) {
            return null;
        }
        String host = p.getAddress().getHostAddress();
        if (own.contains(host)) {
            return null; // este próprio aparelho
        }
        int port = ((b[5] & 0xff) << 8) | (b[6] & 0xff);
        String name = new String(b, 7, len - 7, UTF8);
        return new Found(host, port, name);
    }

    // ------------------------------------------------------------ servidor (receptor)

    /** Responde às sondas dos transmissores com o nome e a porta deste receptor. */
    static final class Responder extends Thread {
        private final String name;
        private volatile DatagramSocket sock;
        private volatile boolean stop = false;

        Responder(String name) {
            super("wifispeaker-discovery");
            this.name = name;
        }

        @Override
        public void run() {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(Proto.DISCOVERY_PORT));
                sock = s;

                byte[] nameBytes = name.getBytes(UTF8);
                int nameLen = Math.min(nameBytes.length, 100);
                byte[] reply = new byte[7 + nameLen];
                putInt(reply, 0, Proto.DISC_MAGIC);
                reply[4] = 2;
                reply[5] = (byte) (Proto.PORT >> 8);
                reply[6] = (byte) Proto.PORT;
                System.arraycopy(nameBytes, 0, reply, 7, nameLen);

                byte[] buf = new byte[64];
                while (!stop) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    if (p.getLength() >= 5 && getInt(buf, 0) == Proto.DISC_MAGIC && buf[4] == 1) {
                        s.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (IOException ignored) {
                // socket fechado ou porta em uso: o receptor continua funcionando sem descoberta
            }
        }

        void shutdown() {
            stop = true;
            DatagramSocket s = sock;
            if (s != null) {
                s.close();
            }
        }
    }

    // ------------------------------------------------------------ utilidades

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !list.contains(b)) {
                        list.add(b);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }

    static Set<String> localAddresses() {
        Set<String> set = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        set.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return set;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }
}
