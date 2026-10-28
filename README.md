# Wi-Fi Speaker

Transforma um celular antigo (Android 5.0 ou mais recente) em receptor de som por Wi-Fi.
O celular novo (Android 10 ou mais recente) envia o áudio que está tocando, e o antigo
toca pela saída P2 (cabo para a caixa de som).

Um único app, dois modos:

- **Receptor** (celular antigo): fica escutando na porta TCP 50505 e toca o áudio recebido.
- **Transmissor** (celular novo): captura o áudio do sistema e envia ao receptor.

> Não é um alto-falante Bluetooth: o celular não aparece na lista de dispositivos Bluetooth.
> O Android não permite que um app assuma o papel de receptor A2DP.

## Como gerar o APK (sem Android Studio)

1. Envie este projeto para o GitHub.
2. Abra a aba **Actions** do repositório e aguarde o workflow **Build APK** terminar
   (ou clique em *Run workflow*).
3. Entre na execução concluída e baixe o artefato **wifi-speaker-apk**.
4. Descompacte e instale `app-debug.apk` nos dois celulares
   (permita a instalação de fontes desconhecidas quando o Android pedir).

## Como usar

1. Ligue os dois celulares na mesma rede Wi-Fi, ou ligue o hotspot de um deles e conecte o outro.
2. No celular antigo, ligado à caixa pelo cabo P2, abra o app, toque em **Iniciar receptor**
   e anote o endereço IP mostrado.
3. No celular novo, abra o app, digite esse IP e toque em **Iniciar transmissor**.
   Autorize a permissão de áudio e a captura do que está tocando.
4. Toque música ou vídeo no celular novo. O som sai no celular antigo.

Para encerrar, toque em **Parar** em cada celular.

## Programa para Windows

A pasta `windows/` tem a versão para PC, com os mesmos dois modos e o mesmo protocolo,
então ela conversa com o app do celular:

- **Transmissor (PC para celular):** envia o som que está tocando no PC ao celular antigo,
  que toca pela saída P2. No PC, digite o IP mostrado no receptor do celular.
- **Receptor (celular para PC):** toca nas caixas do PC o som enviado pelo transmissor
  do celular novo. Digite no celular o IP que o programa mostra.

Para obter o `.exe`: aba **Actions** > **Build Windows** > execução mais recente >
artefato **wifi-speaker-windows**. Ele roda sem instalar nada. Na primeira vez que
iniciar o receptor, permita o acesso no firewall do Windows (redes privadas).

## Limitações

- A captura de áudio só funciona com apps que permitem ser capturados. Alguns apps
  de streaming e jogos bloqueiam e geram silêncio.
- O celular novo continua tocando o som também. Ajuste o volume dele como preferir.
- Há um pequeno atraso (de décimos de segundo), então não é ideal para vídeo com sincronia.
- No celular antigo, deixe o app sem restrição de bateria e, de preferência, na tomada.
- O áudio é enviado sem criptografia pela rede local. Use apenas em redes de confiança.

## Detalhes técnicos

- PCM 16 bits, 44,1 kHz, estéreo, sobre TCP (`TCP_NODELAY`).
- Cabeçalho de 12 bytes (assinatura, taxa de amostragem, canais) e depois o fluxo de áudio.
- Transmissor: `AudioPlaybackCapture` com `MediaProjection` (Android 10+).
- Receptor: `AudioTrack` em serviço de primeiro plano, com `WakeLock` e `WifiLock`.
