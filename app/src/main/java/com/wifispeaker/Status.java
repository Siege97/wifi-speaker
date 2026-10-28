package com.wifispeaker;

/** Estado compartilhado entre os serviços e a tela (a tela consulta a cada meio segundo). */
final class Status {
    private Status() {}

    static volatile boolean receiverRunning = false;
    static volatile String receiver = "Parado";

    static volatile boolean senderRunning = false;
    static volatile String sender = "Parado";
}
