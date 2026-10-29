using NAudio.CoreAudioApi;

namespace WifiSpeaker;

sealed class MainForm : Form
{
    private const int BufMin = 50;
    private const int BufMax = 1000;

    private readonly Settings settings = Settings.Load();

    /// <summary>Buffer de reproducao em ms lido pelo receptor; 0 = padrao.</summary>
    private volatile int currentBufferMs;

    /// <summary>Dispositivo de saida escolhido para o receptor; null = padrao do Windows.</summary>
    private volatile string? currentOutput;

    private const string DefaultOutputLabel = "Padrao do Windows";

    // --- Receptor
    private readonly Button recvButton = new() { Text = "Iniciar receptor", AutoSize = true };
    private readonly Label recvStatus = new() { Text = "Estado: Parado", AutoSize = true, MaximumSize = new Size(440, 0) };
    private readonly Label recvIps = new() { AutoSize = true, MaximumSize = new Size(440, 0) };
    private readonly ComboBox outBox = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 400 };
    private readonly CheckBox bufferDefault = new() { Text = "Usar buffer padrao", AutoSize = true };
    private readonly Label bufferLabel = new() { AutoSize = true };
    private readonly TrackBar bufferTrack = new()
    {
        Minimum = BufMin, Maximum = BufMax, TickFrequency = 50, SmallChange = 10, LargeChange = 50, Width = 400,
    };

    // --- Transmissor
    private readonly CheckBox autoBox = new() { Text = "Descobrir o receptor automaticamente", AutoSize = true };
    private readonly TextBox hostBox = new() { Width = 320, PlaceholderText = "IP do celular, ex.: 192.168.0.25" };
    private readonly RadioButton rbSystem = new() { Text = "Som do computador (o que esta tocando)", AutoSize = true };
    private readonly RadioButton rbMic = new() { Text = "Microfone", AutoSize = true };
    private readonly ComboBox micBox = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 360 };
    private readonly Button sendButton = new() { Text = "Iniciar transmissor", AutoSize = true };
    private readonly Label sendStatus = new() { Text = "Estado: Parado", AutoSize = true, MaximumSize = new Size(440, 0) };

    private CancellationTokenSource? recvCts;
    private CancellationTokenSource? sendCts;

    private sealed class MicItem
    {
        public string? Id { get; }
        public string Name { get; }

        public MicItem(string? id, string name)
        {
            Id = id;
            Name = name;
        }

        public override string ToString() => Name;
    }

    public MainForm()
    {
        Text = "Wi-Fi Speaker";
        Font = new Font("Segoe UI", 10f);
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(500, 760);

        var panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            Padding = new Padding(16),
        };

        panel.Controls.Add(Lbl("Wi-Fi Speaker", 18f, bold: true));
        panel.Controls.Add(Lbl("Os dois aparelhos precisam estar na mesma rede Wi-Fi."));

        // ---------------------------------------------------------------- Receptor
        panel.Controls.Add(Lbl("Receptor", 13f, bold: true, top: 16));
        panel.Controls.Add(Lbl("Toca neste PC o som enviado pelo transmissor do celular. "
            + "Na primeira vez, o Windows pode pedir permissao no firewall: permita em redes privadas. "
            + "Os transmissores da rede encontram este PC sozinhos."));
        panel.Controls.Add(recvButton);
        panel.Controls.Add(recvStatus);
        panel.Controls.Add(recvIps);
        panel.Controls.Add(Lbl("Saida de audio (onde o som e tocado). Para usar como microfone em outros "
            + "programas, escolha o \"CABLE Input\" de um cabo de audio virtual (ex.: VB-Cable). "
            + "Vale a partir da proxima conexao."));
        panel.Controls.Add(outBox);
        panel.Controls.Add(bufferDefault);
        panel.Controls.Add(bufferLabel);
        panel.Controls.Add(bufferTrack);
        panel.Controls.Add(Lbl("Buffer maior = som mais estavel, porem com mais atraso. "
            + "Vale a partir da proxima conexao."));

        // ---------------------------------------------------------------- Transmissor
        panel.Controls.Add(Lbl("Transmissor", 13f, bold: true, top: 16));
        panel.Controls.Add(Lbl("Envia o som deste PC para o celular que esta com o receptor iniciado."));
        panel.Controls.Add(autoBox);
        panel.Controls.Add(hostBox);
        panel.Controls.Add(Lbl("O que transmitir:"));
        panel.Controls.Add(rbSystem);
        panel.Controls.Add(rbMic);
        panel.Controls.Add(micBox);
        panel.Controls.Add(sendButton);
        panel.Controls.Add(sendStatus);

        Controls.Add(panel);

        // ---------------------------------------------------------------- estado salvo
        LoadMics();

        currentOutput = settings.OutputDevice;
        RefreshOutputs();

        bufferTrack.Value = Math.Clamp(settings.BufferMs, BufMin, BufMax);
        bufferDefault.Checked = settings.BufferDefault;
        UpdateBufferUi();

        autoBox.Checked = settings.Auto;
        hostBox.Text = settings.Host;
        hostBox.Enabled = !autoBox.Checked;
        rbMic.Checked = settings.Mic;
        rbSystem.Checked = !settings.Mic;
        micBox.Enabled = rbMic.Checked;

        // ---------------------------------------------------------------- eventos
        outBox.DropDown += (_, _) => RefreshOutputs(); // pega dispositivos instalados depois
        outBox.SelectionChangeCommitted += (_, _) =>
        {
            currentOutput = outBox.SelectedIndex <= 0 ? null : outBox.SelectedItem as string;
            settings.OutputDevice = currentOutput;
        };
        bufferDefault.CheckedChanged += (_, _) => UpdateBufferUi();
        bufferTrack.ValueChanged += (_, _) =>
        {
            if (!bufferDefault.Checked) settings.BufferMs = bufferTrack.Value;
            UpdateBufferUi();
        };
        autoBox.CheckedChanged += (_, _) => hostBox.Enabled = !autoBox.Checked;
        rbMic.CheckedChanged += (_, _) => micBox.Enabled = rbMic.Checked;

        recvButton.Click += (_, _) => ToggleReceiver();
        sendButton.Click += (_, _) => ToggleSender();
        FormClosing += (_, _) =>
        {
            recvCts?.Cancel();
            sendCts?.Cancel();
            CollectSettings();
            settings.Save();
        };
    }

    private static Label Lbl(string text, float size = 10f, bool bold = false, int top = 4)
    {
        return new Label
        {
            Text = text,
            AutoSize = true,
            MaximumSize = new Size(440, 0),
            Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular),
            Margin = new Padding(0, top, 0, 4),
        };
    }

    private void Ui(Action action)
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(action);
        else action();
    }

    private void LoadMics()
    {
        micBox.Items.Add(new MicItem(null, "Microfone padrao"));
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            foreach (var d in enumerator.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active))
                micBox.Items.Add(new MicItem(d.ID, d.FriendlyName));
        }
        catch
        {
            // sem lista: fica so o microfone padrao
        }

        int index = 0;
        for (int i = 0; i < micBox.Items.Count; i++)
        {
            if (micBox.Items[i] is MicItem m && m.Id == settings.MicId)
            {
                index = i;
                break;
            }
        }
        micBox.SelectedIndex = index;
    }

    /// <summary>Recarrega a lista de saidas, mantendo a escolha atual.</summary>
    private void RefreshOutputs()
    {
        string? keep = currentOutput;

        outBox.Items.Clear();
        outBox.Items.Add(DefaultOutputLabel);
        foreach (var name in AudioReceiver.OutputDevices())
            outBox.Items.Add(name);

        // Se o dispositivo salvo nao esta na lista agora, mostra o nome mesmo assim: o receptor avisa
        // na conexao e toca no padrao, em vez de a escolha mudar sozinha.
        if (keep != null && !outBox.Items.Contains(keep))
            outBox.Items.Add(keep);

        outBox.SelectedItem = keep ?? DefaultOutputLabel;
    }

    private void UpdateBufferUi()
    {
        bool def = bufferDefault.Checked;
        bufferTrack.Enabled = !def;
        currentBufferMs = def ? 0 : bufferTrack.Value;
        bufferLabel.Text = def ? "Buffer: padrao" : $"Buffer: {bufferTrack.Value} ms";
        settings.BufferDefault = def;
    }

    private void CollectSettings()
    {
        settings.Auto = autoBox.Checked;
        settings.Host = hostBox.Text.Trim();
        settings.Mic = rbMic.Checked;
        settings.MicId = (micBox.SelectedItem as MicItem)?.Id;
    }

    // ---------------------------------------------------------------- Receptor

    private async void ToggleReceiver()
    {
        if (recvCts != null)
        {
            recvCts.Cancel();
            return;
        }

        var cts = recvCts = new CancellationTokenSource();
        recvButton.Text = "Parar receptor";
        recvIps.Text = "Enderecos deste PC:\r\n" + Net.LocalIps();

        string final = "Parado";
        try
        {
            var receiver = new AudioReceiver(
                m => Ui(() => recvStatus.Text = "Estado: " + m),
                () => currentBufferMs,
                () => currentOutput);
            await Task.Run(() => receiver.RunAsync(cts.Token));
        }
        catch (Exception ex)
        {
            final = "Erro: " + ex.Message;
        }
        finally
        {
            cts.Dispose();
            recvCts = null;
            recvButton.Text = "Iniciar receptor";
            recvIps.Text = "";
        }
        recvStatus.Text = "Estado: " + final;
    }

    // ---------------------------------------------------------------- Transmissor

    private async void ToggleSender()
    {
        if (sendCts != null)
        {
            sendCts.Cancel();
            return;
        }

        bool auto = autoBox.Checked;
        string host = hostBox.Text.Trim();
        if (!auto && host.Length == 0)
        {
            MessageBox.Show(this, "Digite o IP do celular ou ative a descoberta automatica.",
                "Wi-Fi Speaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        bool mic = rbMic.Checked;
        string? micId = (micBox.SelectedItem as MicItem)?.Id;
        CollectSettings();
        settings.Save();

        var cts = sendCts = new CancellationTokenSource();
        sendButton.Text = "Parar transmissor";
        autoBox.Enabled = false;
        hostBox.Enabled = false;
        rbSystem.Enabled = false;
        rbMic.Enabled = false;
        micBox.Enabled = false;

        string final = "Parado";
        try
        {
            var sender = new AudioSender(
                m => Ui(() => sendStatus.Text = "Estado: " + m),
                settings.LastReceiver,
                name => settings.LastReceiver = name);
            await Task.Run(() => sender.RunAsync(auto, host, mic, micId, cts.Token));
        }
        catch (Exception ex)
        {
            final = "Erro: " + ex.Message;
        }
        finally
        {
            cts.Dispose();
            sendCts = null;
            sendButton.Text = "Iniciar transmissor";
            autoBox.Enabled = true;
            hostBox.Enabled = !autoBox.Checked;
            rbSystem.Enabled = true;
            rbMic.Enabled = true;
            micBox.Enabled = rbMic.Checked;
            settings.Save();
        }
        sendStatus.Text = "Estado: " + final;
    }
}
