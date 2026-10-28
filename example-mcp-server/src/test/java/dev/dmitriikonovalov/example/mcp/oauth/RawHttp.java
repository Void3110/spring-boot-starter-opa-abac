package dev.dmitriikonovalov.example.mcp.oauth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One HTTP/1.0 exchange over a plain socket. The JDK's HTTP clients refuse to send a caller-chosen
 * {@code Host} header, and that header is exactly what U5 forges. HTTP/1.0 also keeps the response
 * un-chunked, so the body is simply everything after the header block.
 */
record RawHttp(int status, String head, String body) {

    static RawHttp exchange(int port, String method, String path, String... headers) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.0\r\n");
            boolean hostGiven = false;
            for (String header : headers) {
                request.append(header).append("\r\n");
                hostGiven |= header.regionMatches(true, 0, "Host:", 0, 5);
            }
            if (!hostGiven) {
                request.append("Host: 127.0.0.1:").append(port).append("\r\n");
            }
            if ("POST".equals(method)) {
                request.append("Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
            } else {
                request.append("\r\n");
            }
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int split = response.indexOf("\r\n\r\n");
            String head = split < 0 ? response : response.substring(0, split);
            String body = split < 0 ? "" : response.substring(split + 4);
            int status = Integer.parseInt(head.substring(head.indexOf(' ') + 1, head.indexOf(' ') + 4));
            return new RawHttp(status, head, body);
        }
    }

    /** The value of a response header, case-insensitively, or {@code null}. */
    String header(String name) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
