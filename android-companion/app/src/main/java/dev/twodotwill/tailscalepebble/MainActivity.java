package dev.twodotwill.tailscalepebble;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusView;
    private EditText portInput;
    private EditText tokenInput;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(TailToggleService.PREFS, MODE_PRIVATE);
        requestNotificationPermission();
        setContentView(createView());
        startBridge();
        refreshStatus();
    }

    private LinearLayout createView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("TailToggle Companion");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(14), 0, dp(14));
        root.addView(statusView, matchWrap());

        portInput = new EditText(this);
        portInput.setHint("Local port");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText(String.valueOf(prefs.getInt(TailToggleService.KEY_PORT, TailToggleService.DEFAULT_PORT)));
        root.addView(portInput, matchWrap());

        tokenInput = new EditText(this);
        tokenInput.setHint("Shared token (optional)");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(prefs.getString(TailToggleService.KEY_TOKEN, ""));
        root.addView(tokenInput, matchWrap());

        Button save = new Button(this);
        save.setText("Save and restart bridge");
        save.setOnClickListener(v -> {
            int port = TailToggleService.DEFAULT_PORT;
            try {
                port = Integer.parseInt(portInput.getText().toString().trim());
            } catch (NumberFormatException ignored) {
            }
            prefs.edit()
                    .putInt(TailToggleService.KEY_PORT, port)
                    .putString(TailToggleService.KEY_TOKEN, tokenInput.getText().toString().trim())
                    .apply();
            stopService(new Intent(this, TailToggleService.class));
            startBridge();
            refreshStatus();
        });
        root.addView(save, matchWrap());

        Button connect = new Button(this);
        connect.setText("Connect Tailscale");
        connect.setOnClickListener(v -> {
            TailToggleService.sendTailscaleIntent(this, true);
            refreshStatus();
        });
        root.addView(connect, matchWrap());

        Button disconnect = new Button(this);
        disconnect.setText("Disconnect Tailscale");
        disconnect.setOnClickListener(v -> {
            TailToggleService.sendTailscaleIntent(this, false);
            refreshStatus();
        });
        root.addView(disconnect, matchWrap());

        return root;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private void startBridge() {
        Intent intent = new Intent(this, TailToggleService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void refreshStatus() {
        int port = prefs.getInt(TailToggleService.KEY_PORT, TailToggleService.DEFAULT_PORT);
        boolean vpnActive = TailToggleService.isAnyVpnActive(this);
        boolean tailscaleInstalled = TailToggleService.isTailscaleInstalled(this);
        statusView.setText("Bridge: http://127.0.0.1:" + port + "\n"
                + "Android VPN active: " + (vpnActive ? "yes" : "no") + "\n"
                + "Tailscale installed: " + (tailscaleInstalled ? "yes" : "no"));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
