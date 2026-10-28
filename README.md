# Wi-Fi Speaker

Transforma um celular antigo (Android 5.0 ou mais recente) em receptor de som por Wi-Fi.
Outro celular, ou um PC com Windows, envia o áudio, e o receptor toca pela saída P2
(cabo para a caixa de som).

Um único app, dois modos:

- **Receptor** (aparelho que toca o som): fica escutando na porta TCP 50505 e toca o áudio recebido.
- **Transmissor** (aparelho de onde sai o som): captura o áudio e envia ao receptor.

> Não é um alto-falante Bluetooth: o celular não aparece na lista de dispositivos Bluetooth.
> O Android não permite que um app assuma o papel de receptor A2DP.

## Como gerar o APK (sem Android Studio)

1. Envie este projeto para o GitHub.
2. Abra a aba **Actions** do repositório e aguarde o workflow **Build APK** terminar
   (ou clique em *Run workflow*).
3. Entre na execução concluída e baixe o artefato **wifi-speaker-apk**.
4. Descompacte e instale `app-debug.apk` nos celulares
   (permita a instalação de fontes desconhecidas quando o Android pedir).

> Cada build gera o APK com uma chave de assinatura de depuração diferente. Para instalar uma
> versão nova por cima de uma antiga, desinstale a antiga antes.

## Como usar

1. Ligue os aparelhos na mesma rede Wi-Fi, ou ligue o hotspot de um deles e conecte o outro.
2. No aparelho ligado à caixa pelo cabo P2, abra o app e toque em **Iniciar receptor**.
3. No outro aparelho, escolha o que transmitir e toque em **Iniciar transmissor**.
   Com **Descobrir o receptor automaticamente** ligado (o padrão), não precisa digitar IP.
4. Toque música ou vídeo. O som sai no receptor.

Para encerrar, toque em **Parar** em cada aparelho.

## Recursos

- **Descoberta automática:** o transmissor procura os receptores da rede (broadcast UDP, porta 50506)
  e conecta sozinho. Se houver mais de um, prefere o último que usou. Desligue a opção para digitar o IP.
- **Reconexão automática:** se o Wi-Fi cair ou o receptor reiniciar, o transmissor volta a procurar
  e reconecta. Um transmissor novo sempre assume o receptor, mesmo que a conexão antiga tenha ficado
  "meio aberta".
- **Fonte do áudio (Android):** som dos apps (Android 10 ou mais recente), microfone, ou os dois
  misturados. O microfone funciona em qualquer versão, inclusive no Android 5.1.1.
- **Fonte do áudio (Windows):** som do computador ou microfone (à escolha, com seletor de dispositivo).
  A mistura dos dois não existe na versão Windows.
- **Buffer de reprodução (no receptor):** slider de 50 a 1000 ms, com a caixa **Usar buffer padrão**.
  Com o padrão, o som toca assim que chega. Com um valor próprio, o receptor acumula esse tanto de
  áudio antes de começar a tocar: aguenta melhor as falhas do Wi-Fi, mas aumenta o atraso. O valor
  vale a partir da próxima conexão.

## Programa para Windows

A pasta `windows/` tem a versão para PC, com os mesmos dois modos e o mesmo protocolo,
então ela conversa com o app do celular, nas duas direções.

Para obter o `.exe`: aba **Actions** > **Build Windows** > execução mais recente >
artefato **wifi-speaker-windows**. Ele roda sem instalar nada. Na primeira vez que
iniciar o receptor, permita o acesso no firewall do Windows (redes privadas), senão o
celular não consegue conectar nem descobrir o PC.

## Limitações

- A captura de áudio dos apps só funciona com apps que permitem ser capturados. Alguns apps
  de streaming e jogos bloqueiam e geram silêncio.
- O aparelho que transmite continua tocando o som também. Ajuste o volume dele como preferir.
- Há um pequeno atraso (de décimos de segundo), então não é ideal para vídeo com sincronia.
- A descoberta usa broadcast: não atravessa roteadores nem redes que isolam os aparelhos entre si
  (alguns Wi-Fi de visitantes). Nesses casos, desligue a descoberta e digite o IP.
- No celular receptor, deixe o app sem restrição de bateria e, de preferência, na tomada.
- O áudio é enviado sem criptografia pela rede local. Use apenas em redes de confiança.

## Detalhes técnicos

- PCM 16 bits, 44,1 kHz (no Windows, a taxa do dispositivo), sobre TCP (`TCP_NODELAY`).
- Cabeçalho de 12 bytes (assinatura `WFSP`, taxa de amostragem, canais) e depois o fluxo de áudio.
- Descoberta: UDP na porta 50506. Sonda em broadcast (`WFSD` + byte 1); resposta direta
  (`WFSD` + byte 2 + porta TCP + nome do aparelho).
- Transmissor Android: `AudioPlaybackCapture` com `MediaProjection` e/ou `AudioRecord` do microfone.
- Transmissor Windows: WASAPI (loopback ou captura do microfone), via NAudio.
- Receptor Android: `AudioTrack` em serviço de primeiro plano, com `WakeLock`, `WifiLock` e `MulticastLock`.
- Receptor Windows: `WaveOutEvent` com `BufferedWaveProvider`.
