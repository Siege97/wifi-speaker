using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace WifiSpeaker;

sealed record Found(string Host, int Port, string Name);

/// <summary>Descoberta de receptores na rede local por broadcast UDP (igual ao app Android).</summary>
static class Discovery
{
    // ------------------------------------------------------------ cliente (transmissor)

    /// <summary>Procura receptores por ate timeoutMs. Para cedo se achar o preferido.</summary>
    public static async Task<List<Found>> ProbeAsync(int timeoutMs, string? preferred, CancellationToken ct)
    {
        var found = new List<Found>();
        var own = LocalAddresses();

        using var udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, 0));
        DisableConnReset(udp.Client);

        var packet = new byte[5];
        BinaryPrimitives.WriteInt32BigEndian(packet, Proto.DiscMagic);
        packet[4] = 1;

        var targets = BroadcastTargets();
        long start = Environment.TickCount64;
        long end = start + timeoutMs;
        int sent = 0;

        while (Environment.TickCount64 < end && !ct.IsCancellationRequested)
        {
            if (sent < 3 && Environment.TickCount64 - start >= sent * 500L)
            {
                foreach (var t in targets)
                {
                    try
                    {
                        await udp.SendAsync(packet, packet.Length, new IPEndPoint(t, Proto.DiscoveryPort));
                    }
                    catch (SocketException)
                    {
                        // rede sem esse broadcast: ignora
                    }
                }
                sent++;
            }

            using var slice = CancellationTokenSource.CreateLinkedTokenSource(ct);
            slice.CancelAfter(150);
            try
            {
                var r = await udp.ReceiveAsync(slice.Token);
                var f = Parse(r, own);
                if (f != null && found.All(x => x.Host != f.Host))
                {
                    found.Add(f);
                    if (preferred != null && preferred == f.Name) break;
                    // da mais um instante para outros receptores responderem
                    end = Math.Min(end, Environment.TickCount64 + 300);
                }
            }
            catch (OperationCanceledException)
            {
                // fim da janela de 150 ms (ou cancelamento geral, tratado pelo while)
            }
            catch (SocketException)
            {
            }
        }

        return found;
    }

    /// <summary>Escolhe o receptor de nome preferido, ou o primeiro encontrado.</summary>
    public static async Task<Found?> FindAsync(string? preferred, int timeoutMs, CancellationToken ct)
    {
        var list = await ProbeAsync(timeoutMs, preferred, ct);
        if (list.Count == 0) return null;
        if (preferred != null)
        {
            var match = list.FirstOrDefault(f => f.Name == preferred);
            if (match != null) return match;
        }
        return list[0];
    }

    private static Found? Parse(UdpReceiveResult r, HashSet<string> own)
    {
        var b = r.Buffer;
        if (b.Length < 7 || BinaryPrimitives.ReadInt32BigEndian(b) != Proto.DiscMagic || b[4] != 2)
            return null;

        string host = r.RemoteEndPoint.Address.ToString();
        if (own.Contains(host)) return null; // este proprio PC

        int port = (b[5] << 8) | b[6];
        string name = Encoding.UTF8.GetString(b, 7, b.Length - 7);
        return new Found(host, port, name);
    }

    // ------------------------------------------------------------ servidor (receptor)

    /// <summary>Responde as sondas dos transmissores com o nome e a porta deste receptor.</summary>
    public static async Task RespondAsync(string name, CancellationToken ct)
    {
        UdpClient? udp = null;
        try
        {
            udp = new UdpClient(AddressFamily.InterNetwork);
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, Proto.DiscoveryPort));
            DisableConnReset(udp.Client);

            var nameBytes = Encoding.UTF8.GetBytes(name);
            int n = Math.Min(nameBytes.Length, 100);
            var reply = new byte[7 + n];
            BinaryPrimitives.WriteInt32BigEndian(reply, Proto.DiscMagic);
            reply[4] = 2;
            reply[5] = (byte)((Proto.Port >> 8) & 0xFF);
            reply[6] = (byte)(Proto.Port & 0xFF);
            Array.Copy(nameBytes, 0, reply, 7, n);

            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var r = await udp.ReceiveAsync(ct);
                    if (r.Buffer.Length >= 5
                        && BinaryPrimitives.ReadInt32BigEndian(r.Buffer) == Proto.DiscMagic
                        && r.Buffer[4] == 1)
                    {
                        await udp.SendAsync(reply, reply.Length, r.RemoteEndPoint);
                    }
                }
                catch (SocketException)
                {
                    await Task.Delay(100, ct); // erro passageiro de rede: segue ouvindo
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception)
        {
            // porta em uso ou sem permissao: a descoberta fica desligada,
            // mas conectar pelo IP continua funcionando
        }
        finally
        {
            udp?.Dispose();
        }
    }

    // ------------------------------------------------------------ utilidades

    private static List<IPAddress> BroadcastTargets()
    {
        var set = new HashSet<IPAddress> { IPAddress.Broadcast };
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                    var a = ua.Address.GetAddressBytes();
                    var m = ua.IPv4Mask.GetAddressBytes();
                    var b = new byte[4];
                    for (int i = 0; i < 4; i++) b[i] = (byte)(a[i] | ~m[i]);
                    set.Add(new IPAddress(b));
                }
            }
        }
        catch
        {
            // fica so com 255.255.255.255
        }
        return set.ToList();
    }

    private static HashSet<string> LocalAddresses()
    {
        var set = new HashSet<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily == AddressFamily.InterNetwork)
                    set.Add(ua.Address.ToString());
            }
        }
        catch
        {
            // sem lista: pode acabar achando o proprio PC
        }
        return set;
    }

    /// <summary>
    /// No Windows, um pacote ICMP "porta inalcancavel" faz o proximo ReceiveAsync falhar.
    /// Isso desliga esse comportamento (SIO_UDP_CONNRESET).
    /// </summary>
    private static void DisableConnReset(Socket s)
    {
        try
        {
            s.IOControl(unchecked((int)0x9800000C), new byte[] { 0 }, null);
        }
        catch
        {
            // nao e critico
        }
    }
}
