using System.Buffers.Binary;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace WifiSpeaker;

/// <summary>Erro que nao adianta tentar de novo (formato nao suportado, microfone ausente).</summary>
sealed class FatalException : Exception
{
    public FatalException(string message) : base(message) { }
}

/// <summary>Falha de conexao: mostra a mensagem e tenta de novo.</summary>
sealed class RetryException : Exception
{
    public RetryException(string message) : base(message) { }
}

/// <summary>
/// Captura o som que esta tocando no PC (WASAPI loopback) ou o microfone e envia ao receptor.
/// Se a conexao cair, reconecta sozinho. Pode achar o receptor automaticamente na rede.
/// </summary>
sealed class AudioSender
{
    private readonly Action<string> status;
    private readonly Action<string>? onReceiverFound;
    private string? lastName;

    public AudioSender(Action<string> status, string? lastReceiver, Action<string>? onReceiverFound)
    {
        this.status = status;
        this.lastName = lastReceiver;
        this.onReceiverFound = onReceiverFound;
    }

    /// <param name="auto">Descobrir o receptor na rede (ignora host).</param>
    /// <param name="mic">true = microfone; false = som do computador.</param>
    public async Task RunAsync(bool auto, string host, bool mic, string? micId, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await StreamOnceAsync(auto, host, mic, micId, ct);
                if (!ct.IsCancellationRequested)
                    status("Captura encerrada, reiniciando...");
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (FatalException)
            {
                throw;
            }
            catch (RetryException ex) when (!ct.IsCancellationRequested)
            {
                status(ex.Message);
            }
            catch (Exception) when (!ct.IsCancellationRequested)
            {
                status("Conexao perdida, reconectando...");
            }
            catch (Exception)
            {
                break; // erro causado pelo proprio cancelamento
            }

            if (ct.IsCancellationRequested) break;
            try
            {
                await Task.Delay(1000, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task StreamOnceAsync(bool auto, string host, bool mic, string? micId, CancellationToken ct)
    {
        string target;
        int port = Proto.Port;

        if (auto)
        {
            status("Procurando receptor na rede...");
            var found = await Discovery.FindAsync(lastName, 1500, ct);
            if (ct.IsCancellationRequested) return;
            if (found == null)
                throw new RetryException("Nenhum receptor encontrado, procurando de novo...");

            target = found.Host;
            port = found.Port;
            lastName = found.Name;
            onReceiverFound?.Invoke(found.Name);
        }
        else
        {
            target = host.Trim();
        }

        using var client = new TcpClient { NoDelay = true };
        status($"Conectando a {target}...");
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(4000);
            await client.ConnectAsync(target, port, timeout.Token);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            throw new RetryException($"Sem conexao com {target}, tentando de novo...");
        }

        var stream = client.GetStream();

        using var capture = CreateCapture(mic, micId);
        WaveFormat src = capture.WaveFormat;
        WaveFormat std = src is WaveFormatExtensible ext ? ext.ToStandardWaveFormat() : src;

        bool isFloat = std.Encoding == WaveFormatEncoding.IeeeFloat && std.BitsPerSample == 32;
        bool isPcm16 = std.Encoding == WaveFormatEncoding.Pcm && std.BitsPerSample == 16;
        if (!isFloat && !isPcm16)
            throw new FatalException("formato de audio do Windows nao suportado: " + std);

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
        status($"Transmitindo para {target} ({(mic ? "microfone" : "som do computador")})");

        Exception? error = await done.Task;
        if (error != null && !ct.IsCancellationRequested)
            throw new IOException(error.Message);
    }

    private static WasapiCapture CreateCapture(bool mic, string? micId)
    {
        if (!mic) return new WasapiLoopbackCapture();

        try
        {
            using var enumerator = new MMDeviceEnumerator();
            MMDevice device = micId == null
                ? enumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Console)
                : enumerator.GetDevice(micId);
            return new WasapiCapture(device, false, 50);
        }
        catch (Exception ex)
        {
            throw new FatalException("nao foi possivel abrir o microfone: " + ex.Message);
        }
    }
}
