package dev.codeservergo;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** One code-server endpoint. */
final class Server {

    String id;
    String name;
    String url;
    long lastOpened;

    Server() {
        this.id = UUID.randomUUID().toString();
    }

    Server(String id, String name, String url, long lastOpened) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.lastOpened = lastOpened;
    }

    String host() {
        Uri u = Uri.parse(url);
        String h = u.getHost();
        if (h == null) return url;
        int port = u.getPort();
        return port > 0 ? h + ":" + port : h;
    }

    String origin() {
        Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "http" : u.getScheme();
        String host = u.getHost();
        if (host == null) return scheme + "://" + host();
        int port = u.getPort();
        return scheme + "://" + host + (port > 0 ? ":" + port : "");
    }

    /** Normalises what a human types into something a WebView can load. */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "http://" + s;
        Uri u = Uri.parse(s);
        String scheme = u.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        return s;
    }

    /** Falls back to the host name when the user leaves the name field empty. */
    static String nameOrHost(String name, String url) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        Uri u = Uri.parse(url);
        String host = u.getHost();
        if (host == null) return "code-server";
        int port = u.getPort();
        return port > 0 ? host + ":" + port : host;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("url", url);
        o.put("lastOpened", lastOpened);
        return o;
    }

    static Server fromJson(JSONObject o) {
        Server s = new Server();
        s.id = o.optString("id", s.id);
        s.name = o.optString("name", "");
        s.url = o.optString("url", "");
        s.lastOpened = o.optLong("lastOpened", 0L);
        return s;
    }
}
