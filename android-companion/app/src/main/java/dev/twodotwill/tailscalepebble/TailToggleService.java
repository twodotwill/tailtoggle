package dev.twodotwill.tailscalepebble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TailToggleService extends Service {
    public static final String PREFS = "tailtoggle";
    public static final String KEY_PORT = "port";
    public static final String KEY_TOKEN = "token";
    public static final int DEFAULT_PORT = 17999;

    private static final String CHANNEL_ID = "tailtoggle_bridge";
    private static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";
    private static final String TAILSCALE_RECEIVER = "com.tailscale.ipn.IPNReceiver";
    private static final String ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN";
    private static final String ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN";

    private ExecutorService executor;
    private ServerSocket serverSocket;
    private volatile boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1, notification());
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        executor = Executors.newCachedThreadPool();
        running = true;
        executor.execute(() -> {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
            try (ServerSocket socket = new ServerSocket(port, 20, InetAddress.getByName("127.0.0.1"))) {
                serverSocket = socket;
                while (running) {
                    Socket client = socket.accept();
                    executor.execute(() -> handleClient(client));
                }
            } catch (IOException ignored) {
                running = false;
            }
        });
    }

    private void handleClient(Socket client) {
        try (Socket socket = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream output = socket.getOutputStream()) {
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() == 0) {
                return;
            }

            String tokenHeader = "";
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("X-TailToggle-Token")) {
                    tokenHeader = line.substring(colon + 1).trim();
                }
            }

            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String expectedToken = prefs.getString(KEY_TOKEN, "");
            if (expectedToken != null && expectedToken.length() > 0 && !expectedToken.equals(tokenHeader)) {
                writeJson(output, 401, "{\"error\":\"bad token\"}");
                return;
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0].toUpperCase(Locale.US) : "";
            String path = cleanPath(parts.length > 1 ? parts[1] : "/");
            route(output, method, path);
        } catch (IOException ignored) {
        }
    }

    private void route(OutputStream output, String method, String path) throws IOException {
        if ("OPTIONS".equals(method)) {
            writeJson(output, 204, "");
            return;
        }
        if ("GET".equals(method) && "/status".equals(path)) {
            writeStatus(output, "status");
            return;
        }
        if ("POST".equals(method) && "/connect".equals(path)) {
            writeCommandResult(output, true);
            return;
        }
        if ("POST".equals(method) && "/disconnect".equals(path)) {
            writeCommandResult(output, false);
            return;
        }
        if ("POST".equals(method) && "/toggle".equals(path)) {
            boolean connect = !isAnyVpnActive(this);
            writeCommandResult(output, connect);
            return;
        }
        writeJson(output, 404, "{\"error\":\"not found\"}");
    }

    private void writeCommandResult(OutputStream output, boolean connect) throws IOException {
        if (!isTailscaleInstalled(this)) {
            writeJson(output, 503, "{\"error\":\"Tailscale is not installed\"}");
            return;
        }
        if (!sendTailscaleIntent(this, connect)) {
            writeJson(output, 500, "{\"error\":\"Could not send Tailscale intent\"}");
            return;
        }
        writeStatus(output, connect ? "sent_connect" : "sent_disconnect");
    }

    private void writeStatus(OutputStream output, String status) throws IOException {
        boolean vpnActive = isAnyVpnActive(this);
        boolean installed = isTailscaleInstalled(this);
        String message;
        if ("sent_connect".equals(status)) {
            message = "Tailscale connect intent sent";
        } else if ("sent_disconnect".equals(status)) {
            message = "Tailscale disconnect intent sent";
        } else if (vpnActive) {
            message = "Android reports a VPN is active";
            status = "on";
        } else {
            message = "Android reports no active VPN";
            status = "off";
        }
        String body = "{"
                + "\"status\":\"" + status + "\","
                + "\"vpnActive\":" + vpnActive + ","
                + "\"tailscaleInstalled\":" + installed + ","
                + "\"message\":\"" + json(message) + "\""
                + "}";
        writeJson(output, 200, body);
    }

    private void writeJson(OutputStream output, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = statusCode == 200 ? "OK"
                : statusCode == 204 ? "No Content"
                : statusCode == 401 ? "Unauthorized"
                : statusCode == 404 ? "Not Found"
                : "Error";
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: X-TailToggle-Token, Content-Type, Accept\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
    }

    public static boolean sendTailscaleIntent(Context context, boolean connect) {
        Intent intent = new Intent(connect ? ACTION_CONNECT : ACTION_DISCONNECT);
        intent.setPackage(TAILSCALE_PACKAGE);
        intent.setComponent(new ComponentName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER));
        try {
            context.sendBroadcast(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean isAnyVpnActive(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network[] networks = manager.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTailscaleInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TAILSCALE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification notification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText("Listening on 127.0.0.1")
                .setSmallIcon(R.drawable.ic_tailtoggle)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String cleanPath(String path) {
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return path.length() == 0 ? "/" : path;
    }
}
