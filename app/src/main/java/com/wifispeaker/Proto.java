package com.wifispeaker;

/**
 * Protocolo simples sobre TCP:
 *   cabeçalho: int MAGIC, int sampleRate, int canais   (big-endian)
 *   depois:    PCM 16 bits little-endian, intercalado, até a conexão fechar.
 */
final class Proto {
    private Proto() {}

    static final int PORT = 50505;
    static final int MAGIC = 0x57465350; // "WFSP"
    static final int SAMPLE_RATE = 44100;
    static final int CHANNELS = 2;
    /** Múltiplo de 4 (um frame estéreo 16 bits), mantém o alinhamento do fluxo. */
    static final int CHUNK = 4096;
}
