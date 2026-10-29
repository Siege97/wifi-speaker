using System.Text.Json;

namespace WifiSpeaker;

/// <summary>Preferencias salvas em %APPDATA%\WifiSpeaker\settings.json.</summary>
sealed class Settings
{
    public bool Auto { get; set; } = true;
    public string Host { get; set; } = "";
    public bool Mic { get; set; }
    public string? MicId { get; set; }
    public bool BufferDefault { get; set; } = true;
    public int BufferMs { get; set; } = 300;
    public string? LastReceiver { get; set; }

    /// <summary>Nome do dispositivo de saida do receptor; null = padrao do Windows.</summary>
    public string? OutputDevice { get; set; }

    private static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WifiSpeaker", "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch
        {
            // arquivo ilegivel: comeca do padrao
        }
        return new Settings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this));
        }
        catch
        {
            // nao e critico
        }
    }
}
