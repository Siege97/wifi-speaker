using System.Buffers.Binary;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace WifiSpeaker;

/// <summary>
/// Captura o som que esta tocando no PC (WASAPI loopback) e envia ao receptor do celular.
/// </summary>
sealed class AudioSender
{
    private readonly Action<string> status;

    public AudioSender(Action<string> status) => this.status = status;

    public async Task RunAsync(string host, CancellationToken ct)
    {
        using var client = new TcpClient { NoDelay = true };

        status($"Conectando a {host}...");
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            await client.ConnectAsync(host, Proto.Port, timeout.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new IOException("tempo esgotado ao conectar (confira o IP e se o receptor esta iniciado)");
        }

        var stream = client.GetStream();

        using var capture = new WasapiLoopbackCapture();
        WaveFormat src = capture.WaveFormat;
        WaveFormat std = src is WaveFormatExtensible ext ? ext.ToStandardWaveFormat() : src;

        bool isFloat = std.Encoding == WaveFormatEncoding.IeeeFloat && std.BitsPerSample == 32;
        bool isPcm16 = std.Encoding == WaveFormatEncoding.Pcm && std.BitsPerSample == 16;
        if (!isFloat && !isPcm16)
            throw new NotSupportedException("formato de audio do Windows nao suportado: " + std);

        int align = src.BlockAlign;
        int channels = src.Channels;
        int rate = src.SampleRate;

        // Cabecalho: sempre enviamos estereo 16 bits na taxa do dispositivo.
        var header = new byte[Proto.HeaderSize];
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(0, 4), Proto.Magic);
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(4, 4), rate);
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(8, 4), 2);
        await stream.WriteAsync(header, ct);

        var done = new TaskCompletionSource<Exception?>(TaskCreationOptions.RunContinuationsAsynchronously);
        Exception? failure = null;
        byte[] outBuf = new byte[8192];

        capture.DataAvailable += (_, e) =>
        {
            if (e.BytesRecorded <= 0) return;
            try
            {
                int frames = e.BytesRecorded / align;
                int need = frames * 4;
                if (outBuf.Length < need) outBuf = new byte[need];

                for (int i = 0; i < frames; i++)
                {
                    int pos = i * align;
                    short l, r;
                    if (isFloat)
                    {
                        float lf = BitConverter.ToSingle(e.Buffer, pos);
                        float rf = channels > 1 ? BitConverter.ToSingle(e.Buffer, pos + 4) : lf;
                        l = (short)(Math.Clamp(lf, -1f, 1f) * 32767f);
                        r = (short)(Math.Clamp(rf, -1f, 1f) * 32767f);
                    }
                    else
                    {
                        l = BitConverter.ToInt16(e.Buffer, pos);
                        r = channels > 1 ? BitConverter.ToInt16(e.Buffer, pos + 2) : l;
                    }

                    int o = i * 4;
                    outBuf[o] = (byte)l;
                    outBuf[o + 1] = (byte)(l >> 8);
                    outBuf[o + 2] = (byte)r;
                    outBuf[o + 3] = (byte)(r >> 8);
                }

                stream.Write(outBuf, 0, need);
            }
            catch (Exception ex)
            {
                failure = ex;
                // StopRecording pode esperar a thread de captura: chamar de outra thread evita travar.
                Task.Run(() => capture.StopRecording());
            }
        };

        capture.RecordingStopped += (_, e) => done.TrySetResult(e.Exception ?? failure);

        using var reg = ct.Register(() =>
        {
            client.Close(); // destrava uma escrita de rede pendente
            Task.Run(() => capture.StopRecording());
        });

        capture.StartRecording();
        status($"Transmitindo para {host}");

        Exception? error = await done.Task;
        if (error != null && !ct.IsCancellationRequested)
            throw new IOException(error.Message);
    }
}
