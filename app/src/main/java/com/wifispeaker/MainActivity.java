package com.wifispeaker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1;
    private static final int REQ_AUDIO = 2;
    private static final int REQ_PROJECTION = 3;

    private static final int BUF_MIN_MS = 50;
    private static final int BUF_MAX_MS = 1000;

    private TextView receiverInfo;
    private TextView senderInfo;
    private TextView bufLabel;
    private Button receiverButton;
    private Button senderButton;
    private EditText hostEdit;
    private CheckBox bufDefault;
    private SeekBar bufSeek;
    private CheckBox autoBox;
    private CheckBox appsBox;
    private CheckBox micBox;

    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.sp(this);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(4));
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));

        TextView app = new TextView(this);
        app.setText("Wi-Fi Speaker");
        app.setTextSize(26);
        app.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(app);
        root.addView(body("Os dois aparelhos precisam estar na mesma rede Wi-Fi "
                + "(ou um deles em modo roteador/hotspot)."));

        // --- Receptor
        root.addView(title("Receptor (celular que toca o som)"));
        root.addView(body("Use no celular ligado à caixa de som pelo cabo P2. "
                + "Os transmissores da rede encontram este aparelho sozinhos."));
        receiverButton = new Button(this);
        receiverButton.setOnClickListener(v -> toggleReceiver());
        root.addView(receiverButton);
        receiverInfo = body("");
        root.addView(receiverInfo);

        bufDefault = new CheckBox(this);
        bufDefault.setText("Usar buffer padrão");
        bufDefault.setChecked(prefs.getBoolean("buf_default", true));
        root.addView(bufDefault);

        bufLabel = body("");
        root.addView(bufLabel);
        bufSeek = new SeekBar(this);
        bufSeek.setMax(BUF_MAX_MS - BUF_MIN_MS);
        bufSeek.setProgress(prefs.getInt("buf_ms", 300) - BUF_MIN_MS);
        root.addView(bufSeek);
        root.addView(body("Buffer maior = som mais estável, porém com mais atraso. "
                + "Vale a partir da próxima conexão."));

        bufDefault.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("buf_default", checked).apply();
            updateBufferUi();
        });
        bufSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt("buf_ms", progress + BUF_MIN_MS).apply();
                }
                updateBufferUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        updateBufferUi();

        // --- Transmissor
        root.addView(title("Transmissor (celular de onde sai o som)"));

        autoBox = new CheckBox(this);
        autoBox.setText("Descobrir o receptor automaticamente");
        autoBox.setChecked(prefs.getBoolean("auto", true));
        root.addView(autoBox);

        hostEdit = new EditText(this);
        hostEdit.setHint("IP do receptor, ex.: 192.168.0.25");
        hostEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        hostEdit.setSingleLine(true);
        hostEdit.setText(prefs.getString("host", ""));
        hostEdit.setEnabled(!autoBox.isChecked());
        root.addView(hostEdit);
        autoBox.setOnCheckedChangeListener((b, checked) -> {
            hostEdit.setEnabled(!checked);
            prefs.edit().putBoolean("auto", checked).apply();
        });

        root.addView(body("O que transmitir:"));
        appsBox = new CheckBox(this);
        appsBox.setText("Som dos apps");
        micBox = new CheckBox(this);
        micBox.setText("Microfone");
        boolean canCaptureApps = Build.VERSION.SDK_INT >= 29;
        appsBox.setChecked(canCaptureApps && prefs.getBoolean("apps", true));
        appsBox.setEnabled(canCaptureApps);
        micBox.setChecked(prefs.getBoolean("mic", !canCaptureApps));
        root.addView(appsBox);
        root.addView(micBox);
        if (!canCaptureApps) {
            root.addView(body("Este celular tem Android " + Build.VERSION.RELEASE
                    + ": o som dos apps exige Android 10 ou mais recente. O microfone funciona."));
        } else {
            root.addView(body("Marcando os dois, o microfone é misturado ao som dos apps."));
        }
        appsBox.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("apps", checked).apply());
        micBox.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("mic", checked).apply());

        senderButton = new Button(this);
        senderButton.setOnClickListener(v -> toggleSender());
        root.addView(senderButton);
        senderInfo = body("");
        root.addView(senderInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private void updateBufferUi() {
        boolean def = bufDefault.isChecked();
        bufSeek.setEnabled(!def);
        bufLabel.setText(def ? "Buffer: padrão"
                : "Buffer: " + (bufSeek.getProgress() + BUF_MIN_MS) + " ms");
    }

    private void refresh() {
        receiverButton.setText(Status.receiverRunning ? "Parar receptor" : "Iniciar receptor");
        String info = "Estado: " + Status.receiver;
        if (Status.receiverRunning) {
            info += "\n\nEndereços deste aparelho:\n" + localIps();
        }
        receiverInfo.setText(info);

        senderButton.setText(Status.senderRunning ? "Parar transmissor" : "Iniciar transmissor");
        senderInfo.setText("Estado: " + Status.sender);
    }

    private static String localIps() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        sb.append(ni.getName()).append(": ").append(a.getHostAddress()).append('\n');
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "(nenhuma rede encontrada, conecte-se ao Wi-Fi)" : sb.toString().trim();
    }

    // ---------------------------------------------------------------- Receptor

    private void toggleReceiver() {
        Intent i = new Intent(this, ReceiverService.class);
        if (Status.receiverRunning) {
            stopService(i);
        } else {
            Status.receiver = "Iniciando...";
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        }
        refresh();
    }

    // ---------------------------------------------------------------- Transmissor

    private void toggleSender() {
        if (Status.senderRunning) {
            stopService(new Intent(this, SenderService.class));
            refresh();
            return;
        }

        if (!appsBox.isChecked() && !micBox.isChecked()) {
            Toast.makeText(this, "Marque ao menos uma fonte: som dos apps ou microfone",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String host = hostEdit.getText().toString().trim();
        if (!autoBox.isChecked() && host.isEmpty()) {
            Toast.makeText(this, "Digite o IP do receptor ou ative a descoberta automática",
                    Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString("host", host).apply();

        // A permissão de áudio vale tanto para o microfone quanto para a captura dos apps.
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        beginSender();
    }

    private void beginSender() {
        if (appsBox.isChecked()) {
            // O som dos apps exige a autorização de captura do sistema.
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } else {
            startSender(0, null);
        }
    }

    private void startSender(int resultCode, Intent data) {
        Intent i = new Intent(this, SenderService.class);
        i.putExtra(SenderService.EXTRA_HOST, hostEdit.getText().toString().trim());
        i.putExtra(SenderService.EXTRA_AUTO, autoBox.isChecked());
        i.putExtra(SenderService.EXTRA_APPS, appsBox.isChecked());
        i.putExtra(SenderService.EXTRA_MIC, micBox.isChecked());
        if (data != null) {
            i.putExtra(SenderService.EXTRA_CODE, resultCode);
            i.putExtra(SenderService.EXTRA_DATA, data);
        }
        Status.sender = "Iniciando...";
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginSender();
            } else {
                Toast.makeText(this, "Sem a permissão de áudio o transmissor não funciona",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Captura de áudio não autorizada", Toast.LENGTH_SHORT).show();
            return;
        }
        startSender(resultCode, data);
    }
}
