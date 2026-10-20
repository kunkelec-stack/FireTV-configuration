package tv.evma.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/** Five minute, foreground-only photo receiver. No accounts, external hosts, or permanent server. */
final class PhotoUpload implements AutoCloseable {
    final String pin = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final String url;
    private final ServerSocket server;
    private volatile Socket client;
    private volatile boolean closed;
    private final WallpaperStore store;
    private final Consumer<String> added;
    private final long expires = System.currentTimeMillis() + 300000;
    private int failures;
    PhotoUpload(WallpaperStore store, Consumer<String> added) throws IOException {
        this.store = store; this.added = added;
        String address = null;
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress candidate : Collections.list(network.getInetAddresses()))
                if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) { address = candidate.getHostAddress(); break; }
            if (address != null) break;
        }
        if (address == null) throw new IOException("Connect the TV to your home network first.");
        server = new ServerSocket(0, 2, InetAddress.getByName(address));
        server.setSoTimeout(1000);
        url = "http://" + address + ":" + server.getLocalPort() + "/";
        Thread thread = new Thread(this::serve, "EVMA-photo-upload"); thread.setDaemon(true); thread.start();
    }
    private void serve() {
        int requests = 0;
        while (!closed && System.currentTimeMillis() < expires && failures < 8 && requests < 40) {
            try (Socket socket = server.accept()) {
                client = socket; socket.setSoTimeout(15000); requests++;
                handle(socket);
            } catch (java.net.SocketTimeoutException ignored) {
                // Check expiry between connections and abandon stalled clients.
            } catch (IOException | RuntimeException ignored) {
                // No request bodies, PINs, or local network details are logged.
            } finally { client = null; }
        }
        close();
    }
    private void handle(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int tail = 0;
        while (header.size() < 16384) {
            int value = input.read(); if (value < 0) return;
            header.write(value); tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) break;
        }
        if (tail != 0x0d0a0d0a) { reply(socket, 400, "text/plain", "Invalid request"); return; }
        String[] lines = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length != 3) { reply(socket, 400, "text/plain", "Invalid request"); return; }
        if ("GET".equals(request[0]) && "/".equals(request[1])) { reply(socket, 200, "text/html", PAGE); return; }
        if (!"POST".equals(request[0]) || !request[1].startsWith("/upload?code=")) {
            reply(socket, 404, "text/plain", "Not found"); return;
        }
        String code = request[1].substring("/upload?code=".length());
        if (!MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
            failures++; reply(socket, 403, "text/plain", "Incorrect PIN. Read the current PIN on your TV."); return;
        }
        long length = -1; String host = "", origin = ""; boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':'); if (colon < 0) continue;
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if ("content-length".equals(name)) { try { length = Long.parseLong(value); } catch (NumberFormatException ignored) { length = -1; } }
            if ("host".equals(name)) host = value;
            if ("origin".equals(name)) origin = value;
            if ("transfer-encoding".equals(name)) chunked = true;
        }
        if (!origin.isEmpty() && !origin.equals("http://" + host)) { reply(socket, 403, "text/plain", "Open the address shown on the TV."); return; }
        if (chunked || length < 1 || length > 12 * 1024 * 1024) { reply(socket, 413, "text/plain", "Choose an image under 12 MB."); return; }
        byte[] data = new byte[(int) length]; int count = 0;
        while (count < data.length) {
            if (closed || System.currentTimeMillis() >= expires) return;
            int read = input.read(data, count, data.length - count); if (read < 0) return; count += read;
        }
        if (closed) return;
        try {
            String id = store.add(data);
            reply(socket, 200, "text/plain", "Photo saved. Use your TV remote to preview and apply it.");
            added.accept(id);
        } catch (IOException e) { reply(socket, 400, "text/plain", e.getMessage()); }
    }
    private static void reply(Socket socket, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " Response\r\nContent-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'\r\n\r\n";
        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        Socket socket = client; if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
    private static final String PAGE = "<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>EVMA wallpapers</title><style>body{background:#101e30;color:#fff;font:18px system-ui;max-width:550px;margin:8vh auto;padding:24px}input,button{font:inherit;margin:12px 0;padding:12px;max-width:90%}button{cursor:pointer}p{line-height:1.5}</style>"
            + "<h1>EVMA ♥ Wallpapers</h1><p>Enter the PIN shown on your TV. Choose a JPG, PNG or WebP photo under 12 MB. Photos stay on your TV.</p>"
            + "<form><input id='pin' placeholder='TV PIN' required maxlength='8' autocomplete='off'><br><input id='photo' type='file' accept='image/jpeg,image/png,image/webp' required><br><button>Send to TV</button></form><p id='status'></p>"
            + "<script>document.querySelector('form').onsubmit=async e=>{e.preventDefault();let s=document.querySelector('#status'),f=document.querySelector('#photo').files[0];if(!f||f.size>12582912){s.textContent='Choose a photo under 12 MB.';return;}s.textContent='Uploading…';try{let r=await fetch('/upload?code='+encodeURIComponent(document.querySelector('#pin').value.trim()),{method:'POST',body:f});s.textContent=await r.text()}catch(e){s.textContent='Connection closed. Reopen Upload on your TV for a new address and PIN.'}}</script></html>";
}
