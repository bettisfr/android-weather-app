package it.skynow.app.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Blocking HTTP GET helpers; call them from {@link Background#IO}, never the main thread. */
public final class HttpText {
    private static final int DEFAULT_TIMEOUT_MS = 10000;

    /** Reads a response body as it arrives. */
    public interface BodyParser<T> {
        T parse(InputStream body) throws Exception;
    }

    private HttpText() {}

    public static String read(String address) throws IOException {
        return read(address, DEFAULT_TIMEOUT_MS);
    }

    /** Fetches {@code address} as UTF-8 text, sending each name/value pair in {@code headers}. */
    public static String read(String address, int timeoutMs, String... headers) throws IOException {
        return new String(bytes(address, timeoutMs, headers), StandardCharsets.UTF_8);
    }

    /** Fetches {@code address}; an HTTP error status throws, as {@link HttpURLConnection#getInputStream} does. */
    public static byte[] bytes(String address, int timeoutMs, String... headers) throws IOException {
        HttpURLConnection connection = open(address, timeoutMs, headers);
        try (InputStream stream = connection.getInputStream()) {
            return drain(stream);
        } finally {
            connection.disconnect();
        }
    }

    /** Like {@link #bytes}, but returns null for any status other than 200 OK. */
    public static byte[] bytesIfOk(String address, int timeoutMs, String... headers) throws IOException {
        HttpURLConnection connection = open(address, timeoutMs, headers);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream stream = connection.getInputStream()) {
                return drain(stream);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Passes the response body to {@code parser} without buffering it first. */
    public static <T> T stream(String address, int timeoutMs, BodyParser<T> parser) throws Exception {
        HttpURLConnection connection = open(address, timeoutMs);
        try (InputStream stream = connection.getInputStream()) {
            return parser.parse(stream);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address, int timeoutMs, String... headers) throws IOException {
        if (headers.length % 2 != 0) throw new IllegalArgumentException("Headers must be name/value pairs");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        for (int i = 0; i < headers.length; i += 2) connection.setRequestProperty(headers[i], headers[i + 1]);
        return connection;
    }

    private static byte[] drain(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }
}
