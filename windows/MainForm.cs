namespace WifiSpeaker;

sealed class MainForm : Form
{
    private static readonly string HostFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WifiSpeaker", "host.txt");

    private readonly Button recvButton = new() { Text = "Iniciar receptor", AutoSize = true };
    private readonly Label recvStatus = new() { Text = "Estado: Parado", AutoSize = true, MaximumSize = new Size(440, 0) };
    private readonly Label recvIps = new() { AutoSize = true, MaximumSize = new Size(440, 0) };

    private readonly TextBox hostBox = new() { Width = 320, PlaceholderText = "IP do celular, ex.: 192.168.0.25" };
    private readonly Button sendButton = new() { Text = "Iniciar transmissor", AutoSize = true };
    private readonly Label sendStatus = new() { Text = "Estado: Parado", AutoSize = true, MaximumSize = new Size(440, 0) };

    private CancellationTokenSource? recvCts;
    private CancellationTokenSource? sendCts;

    public MainForm()
    {
        Text = "Wi-Fi Speaker";
        Font = new Font("Segoe UI", 10f);
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(500, 520);

        var panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            Padding = new Padding(16),
        };

        panel.Controls.Add(Text_("Wi-Fi Speaker", 18f, bold: true));
        panel.Controls.Add(Text_("Os dois aparelhos precisam estar na mesma rede Wi-Fi."));

        panel.Controls.Add(Text_("Receptor", 13f, bold: true, top: 16));
        panel.Controls.Add(Text_("Toca neste PC o som enviado pelo transmissor do celular. "
            + "Na primeira vez, o Windows pode pedir permissao no firewall: permita em redes privadas."));
        panel.Controls.Add(recvButton);
        panel.Controls.Add(recvStatus);
        panel.Controls.Add(recvIps);

        panel.Controls.Add(Text_("Transmissor", 13f, bold: true, top: 16));
        panel.Controls.Add(Text_("Envia o som que esta tocando neste PC para o celular "
            + "que esta com o receptor iniciado. Digite o IP mostrado no celular."));
        panel.Controls.Add(hostBox);
        panel.Controls.Add(sendButton);
        panel.Controls.Add(sendStatus);

        Controls.Add(panel);

        try
        {
            if (File.Exists(HostFile)) hostBox.Text = File.ReadAllText(HostFile).Trim();
        }
        catch
        {
            // sem IP salvo
        }

        recvButton.Click += (_, _) => ToggleReceiver();
        sendButton.Click += (_, _) => ToggleSender();
        FormClosing += (_, _) =>
        {
            recvCts?.Cancel();
            sendCts?.Cancel();
        };
    }

    private static Label Text_(string text, float size = 10f, bool bold = false, int top = 4)
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

    private async void ToggleReceiver()
    {
        if (recvCts != null)
        {
            recvCts.Cancel();
            return;
        }

        var cts = recvCts = new CancellationTokenSource();
        recvButton.Text = "Parar receptor";
        recvIps.Text = "Enderecos deste PC (use um deles no transmissor do celular):\r\n" + Net.LocalIps();

        string final = "Parado";
        try
        {
            var receiver = new AudioReceiver(m => Ui(() => recvStatus.Text = "Estado: " + m));
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

    private async void ToggleSender()
    {
        if (sendCts != null)
        {
            sendCts.Cancel();
            return;
        }

        string host = hostBox.Text.Trim();
        if (host.Length == 0)
        {
            MessageBox.Show(this, "Digite o IP do celular (o receptor mostra o IP na tela).",
                "Wi-Fi Speaker", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(HostFile)!);
            File.WriteAllText(HostFile, host);
        }
        catch
        {
            // nao e critico
        }

        var cts = sendCts = new CancellationTokenSource();
        sendButton.Text = "Parar transmissor";
        hostBox.Enabled = false;

        string final = "Parado";
        try
        {
            var sender = new AudioSender(m => Ui(() => sendStatus.Text = "Estado: " + m));
            await Task.Run(() => sender.RunAsync(host, cts.Token));
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
            hostBox.Enabled = true;
        }
        sendStatus.Text = "Estado: " + final;
    }
}
