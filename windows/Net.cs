using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace WifiSpeaker;

static class Net
{
    /// <summary>Enderecos IPv4 das redes ativas deste PC.</summary>
    public static string LocalIps()
    {
        var sb = new StringBuilder();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily == AddressFamily.InterNetwork)
                        sb.AppendLine($"{ni.Name}: {ua.Address}");
                }
            }
        }
        catch
        {
            // ignora: mostra a mensagem padrao abaixo
        }

        return sb.Length == 0
            ? "(nenhuma rede encontrada, conecte-se ao Wi-Fi)"
            : sb.ToString().TrimEnd();
    }
}
