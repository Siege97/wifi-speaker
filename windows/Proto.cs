namespace WifiSpeaker;

/// <summary>
/// Mesmo protocolo do app Android.
///
/// Audio (TCP, porta Port):
///   cabecalho: int MAGIC, int sampleRate, int canais (big-endian)
///   depois:    PCM 16 bits little-endian intercalado, ate a conexao fechar.
///
/// Descoberta (UDP, porta DiscoveryPort):
///   sonda:    int DiscMagic, byte 1                              (broadcast)
///   resposta: int DiscMagic, byte 2, short portaTcp, nome UTF-8  (unicast)
/// </summary>
static class Proto
{
    public const int Port = 50505;
    public const int Magic = 0x57465350; // "WFSP"
    public const int HeaderSize = 12;

    public const int DiscoveryPort = 50506;
    public const int DiscMagic = 0x57465344; // "WFSD"
}
