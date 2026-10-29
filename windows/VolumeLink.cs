using System.Net.Sockets;
using System.Text;

namespace WifiSpeaker;

/// <summary>
/// Conexao de controle do volume (TCP, texto), separada do audio. Uma mensagem por linha:
/// "VOL n", n de 0 a 100. Funciona nos dois sentidos e dos dois lados (receptor e transmissor).
/// </summary>
sealed class VolumeLink
{
    private readonly TcpClient client;
    private readonly object writeLock = new();
    private int pending = -1;
    private int sending;

    /// <summary>Volume recebido do outro aparelho.</summary>
    public event Action<int>? Received;

    public VolumeLink(TcpClient client)
    {
        this.client = client;
        client.NoDelay = true;
    }

    /// <summary>
    /// Envia o volume em segundo plano, sem travar a tela. Se varias mudancas chegarem juntas
    /// (arrastando o slider), so a ultima e enviada, e sempre na ordem certa.
    /// </summary>
    public void Send(int volume)
    {
        Volatile.Write(ref pending, Math.Clamp(volume, 0, 100));
        if (Interlocked.CompareExchange(ref sending, 1, 0) == 0)
            Task.Run(Drain);
    }

    private void Drain()
    {
        try
        {
            while (true)
            {
                int v = Interlocked.Exchange(ref pending, -1);
                if (v < 0) break;
                Write(v);
            }
        }
        finally
        {
            Volatile.Write(ref sending, 0);
            // mudanca que chegou entre o ultimo Exchange e o Write acima
            if (Volatile.Read(ref pending) >= 0 && Interlocked.CompareExchange(ref sending, 1, 0) == 0)
                Task.Run(Drain);
        }
    }

    private void Write(int v)
    {
        try
        {
            var bytes = Encoding.ASCII.GetBytes($"VOL {v}\n");
            lock (writeLock)
            {
                var s = client.GetStream();
                s.Write(bytes, 0, bytes.Length);
                s.Flush();
            }
        }
        catch
        {
            // conexao caiu: a leitura percebe e encerra
        }
    }

    /// <summary>Le mensagens ate a conexao fechar.</summary>
    public async Task ReadLoopAsync(CancellationToken ct)
    {
        try
        {
            using var reader = new StreamReader(client.GetStream(), Encoding.ASCII);
            while (!ct.IsCancellationRequested)
            {
                string? line = await reader.ReadLineAsync(ct);
                if (line == null) break;
                if (line.StartsWith("VOL ") && int.TryParse(line.AsSpan(4), out int v))
                    Received?.Invoke(Math.Clamp(v, 0, 100));
            }
        }
        catch
        {
            // fim da conexao ou cancelamento
        }
    }

    public void Close()
    {
        try
        {
            client.Close();
        }
        catch
        {
        }
    }
}
