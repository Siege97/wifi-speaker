using NAudio.Wave;

namespace WifiSpeaker;

/// <summary>
/// Aplica o volume (0 a 100%) ao audio PCM 16 bits na hora em que o Windows o consome.
/// Assim o slider responde rapido, mesmo com o buffer de reproducao cheio.
/// </summary>
sealed class GainProvider : IWaveProvider
{
    private readonly IWaveProvider source;
    private readonly Func<float> gain;

    public GainProvider(IWaveProvider source, Func<float> gain)
    {
        this.source = source;
        this.gain = gain;
    }

    public WaveFormat WaveFormat => source.WaveFormat;

    public int Read(byte[] buffer, int offset, int count)
    {
        int n = source.Read(buffer, offset, count);
        float g = gain();
        if (g >= 0.999f) return n;

        int end = offset + n - 1;
        for (int i = offset; i < end; i += 2)
        {
            short s = (short)(buffer[i] | (buffer[i + 1] << 8));
            s = (short)(s * g);
            buffer[i] = (byte)s;
            buffer[i + 1] = (byte)(s >> 8);
        }
        return n;
    }
}
