namespace WifiSpeaker;

/// <summary>
/// Mesmo protocolo do app Android:
///   cabecalho: int MAGIC, int sampleRate, int canais (big-endian)
///   depois:    PCM 16 bits little-endian intercalado, ate a conexao fechar.
/// </summary>
static class Proto
{
    public const int Port = 50505;
    public const int Magic = 0x57465350; // "WFSP"
    public const int HeaderSize = 12;
}
