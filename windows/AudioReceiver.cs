using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using NAudio.Wave;

namespace WifiSpeaker;

/// <summary>
/// Escuta na porta TCP e toca, nas caixas do PC, o audio enviado pelo transmissor do celular.
/// Tambem responde a descoberta automatica dos transmissores.
/// </summary>
sealed class AudioReceiver
{
    private readonly Action<string> status;
    private readonly Func<int> bufferMs;

    /// <param name="bufferMs">Buffer de reproducao em ms; 0 = padrao (toca na hora).</param>
    public AudioReceiver(Action<string> status, Func<int> bufferMs)
    {
        this.status = status;
        this.bufferMs = bufferMs;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        var listener = new TcpListener(IPAddress.Any, Proto.Port);
        listener.Start();
        using var reg = ct.Register(() => listener.Stop());

        _ = Discovery.RespondAsync(Environment.MachineName, ct);

        CancellationTokenSource? current = null;
        try
        {
            status("Aguardando o transmissor...");
            while (!ct.IsCancellationRequested)
            {
                TcpClient client;
                try
                {
                    client = await listener.AcceptTcpClientAsync(ct);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception) when (ct.IsCancellationRequested)
                {
                    break;
                }

                // Um transmissor novo sempre assume. Isso permite reconectar depois de uma queda
                // do Wi-Fi: a conexao antiga pode ficar "meio aberta", sem nunca dar erro.
                var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                var old = Interlocked.Exchange(ref current, cts);
                try
                {
                    old?.Cancel();
                }
                catch (ObjectDisposedException)
                {
                }

                _ = HandleAsync(client, cts, () => ReferenceEquals(Volatile.Read(ref current), cts));
            }
        }
        finally
        {
            try
            {
                current?.Cancel();
            }
            catch (ObjectDisposedException)
            {
            }
            listener.Stop();
        }
    }

    private async Task HandleAsync(TcpClient client, CancellationTokenSource cts, Func<bool> isCurrent)
    {
        using (client)
        {
            try
            {
                await PlayAsync(client, cts.Token);
            }
            catch (OperationCanceledException)
            {
                // encerrado pelo usuario ou substituido por um transmissor novo
            }
            catch (Exception ex) when (!cts.IsCancellationRequested)
            {
                status("Conexao encerrada: " + ex.Message);
            }
            catch (Exception)
            {
            }
        }

        if (isCurrent() && !cts.IsCancellationRequested)
            status("Aguardando o transmissor...");
    }

    private async Task PlayAsync(TcpClient client, CancellationToken ct)
    {
        client.NoDelay = true;
        var stream = client.GetStream();

        var header = new byte[Proto.HeaderSize];
        await ReadExactAsync(stream, header, ct);

        int magic = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(0, 4));
        int rate = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(4, 4));
        int channels = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(8, 4));
        if (magic != Proto.Magic)
            throw new InvalidDataException("protocolo invalido");
        if (rate < 8000 || rate > 192000 || (channels != 1 && channels != 2))
            throw new InvalidDataException("formato de audio invalido");

        var remote = (client.Client.RemoteEndPoint as IPEndPoint)?.Address;
        status("Conectado a " + remote);

        var format = new WaveFormat(rate, 16, channels);

        // ms == 0: padrao (toca na hora). Caso contrario, acumula esse tanto de audio antes de
        // comecar a tocar: mais estavel contra falhas do Wi-Fi, porem com mais atraso.
        int ms = Math.Max(0, bufferMs());
        var buffer = new BufferedWaveProvider(format)
        {
            BufferDuration = TimeSpan.FromMilliseconds(Math.Max(2000, ms * 3)),
            DiscardOnBufferOverflow = true,
        };

        using var output = new WaveOutEvent { DesiredLatency = 150 };
        output.Init(buffer);

        bool started = false;
        if (ms <= 0)
        {
            output.Play();
            started = true;
        }

        int align = format.BlockAlign;
        var buf = new byte[16384];
        int have = 0;
        while (true)
        {
            int n = await stream.ReadAsync(buf.AsMemory(have), ct);
            if (n == 0) break;
            have += n;

            // so entrega frames completos ao buffer de saida
            int usable = have - have % align;
            if (usable > 0)
            {
                buffer.AddSamples(buf, 0, usable);
                have -= usable;
                if (have > 0) Buffer.BlockCopy(buf, usable, buf, 0, have);
            }

            if (!started && buffer.BufferedDuration.TotalMilliseconds >= ms)
            {
                output.Play();
                started = true;
            }
        }
    }

    private static async Task ReadExactAsync(Stream s, byte[] buf, CancellationToken ct)
    {
        int off = 0;
        while (off < buf.Length)
        {
            int n = await s.ReadAsync(buf.AsMemory(off), ct);
            if (n == 0) throw new EndOfStreamException();
            off += n;
        }
    }
}
