package dev.codeservergo;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL handling without Android dependencies, so it can be unit tested on a plain JVM.
 *
 * <p>Everything a user types into the address field goes through here: people type
 * {@code 192.168.1.20:8080}, {@code localhost:8080}, or paste a full workspace URL.
 */
final class Urls {

    private Urls() {
    }

    /** Returns a loadable http(s) URL, or null when the input cannot be understood. */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "http://" + s;

        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
        return s;
    }

    /** Falls back to the host (plus port) when the user leaves the name field empty. */
    static String nameOrHost(String name, String url) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String host = host(url);
        return host == null ? "code-server" : host;
    }

    /** host[:port] of the URL, or null. */
    static String host(String url) {
        URI uri = parse(url);
        if (uri == null || uri.getHost() == null) return null;
        int port = uri.getPort();
        return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
    }

    /** The bare host without the port. */
    static String hostOnly(String url) {
        URI uri = parse(url);
        return uri == null ? null : uri.getHost();
    }

    /** scheme://host[:port] with no path, query or fragment. */
    static String origin(String url) {
        URI uri = parse(url);
        if (uri == null || uri.getHost() == null) return null;
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
        int port = uri.getPort();
        return scheme + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
    }

    private static URI parse(String url) {
        if (url == null) return null;
        try {
            return new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
