package com.wifispeaker;

/**
 * Protocolo simples sobre TCP:
 *   cabeçalho: int MAGIC, int sampleRate, int canais   (big-endian)
 *   depois:    PCM 16 bits little-endian, intercalado, até a conexão fechar.
 *
 * Descoberta (UDP, porta DISCOVERY_PORT):
 *   sonda:    int DISC_MAGIC, byte 1                              (broadcast)
 *   resposta: int DISC_MAGIC, byte 2, short portaTcp, nome UTF-8  (unicast)
 */
final class Proto {
    private Proto() {}

    static final int PORT = 50505;
    static final int MAGIC = 0x57465350; // "WFSP"
    static final int SAMPLE_RATE = 44100;
    /** Múltiplo de 4 (um frame estéreo 16 bits), mantém o alinhamento do fluxo. */
    static final int CHUNK = 4096;

    static final int DISCOVERY_PORT = 50506;
    static final int DISC_MAGIC = 0x57465344; // "WFSD"

    /** Controle (TCP, texto): o transmissor conecta aqui; linhas "VOL n" (n de 0 a 100) nos dois sentidos. */
    static final int CONTROL_PORT = 50507;
}
