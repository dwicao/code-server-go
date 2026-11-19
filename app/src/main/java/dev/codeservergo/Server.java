package dev.codeservergo;

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
        String h = Urls.host(url);
        return h == null ? url : h;
    }

    String origin() {
        String o = Urls.origin(url);
        return o == null ? url : o;
    }

    /** Normalises what a human types into something a WebView can load. */
    static String normalize(String raw) {
        return Urls.normalize(raw);
    }

    /** Falls back to the host name when the user leaves the name field empty. */
    static String nameOrHost(String name, String url) {
        return Urls.nameOrHost(name, url);
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
