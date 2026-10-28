using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using NAudio.Wave;

namespace WifiSpeaker;

/// <summary>
/// Escuta na porta TCP e toca, nas caixas do PC, o audio enviado pelo transmissor do celular.
/// </summary>
sealed class AudioReceiver
{
    private readonly Action<string> status;

    public AudioReceiver(Action<string> status) => this.status = status;

    public async Task RunAsync(CancellationToken ct)
    {
        var listener = new TcpListener(IPAddress.Any, Proto.Port);
        listener.Start();
        using var reg = ct.Register(() => listener.Stop());

        try
        {
            while (!ct.IsCancellationRequested)
            {
                status("Aguardando o transmissor...");

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

                using (client)
                {
                    try
                    {
                        await PlayAsync(client, ct);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                    catch (Exception) when (ct.IsCancellationRequested)
                    {
                        break;
                    }
                    catch (Exception ex)
                    {
                        // conexao encerrada ou invalida: volta a esperar
                        status("Conexao encerrada: " + ex.Message);
                    }
                }
            }
        }
        finally
        {
            listener.Stop();
        }
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
        var buffer = new BufferedWaveProvider(format)
        {
            BufferDuration = TimeSpan.FromSeconds(2),
            DiscardOnBufferOverflow = true,
        };

        using var output = new WaveOutEvent { DesiredLatency = 150 };
        output.Init(buffer);
        output.Play();

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
