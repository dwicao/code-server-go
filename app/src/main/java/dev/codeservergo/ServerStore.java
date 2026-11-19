package dev.codeservergo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Server list persistence (a compact JSON array inside SharedPreferences). */
final class ServerStore {

    private static final String KEY = "servers";

    private final SharedPreferences sp;

    ServerStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("codeservergo", Context.MODE_PRIVATE);
    }

    List<Server> all() {
        List<Server> out = new ArrayList<>();
        String raw = sp.getString(KEY, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    Server s = Server.fromJson(arr.getJSONObject(i));
                    if (s.url != null && !s.url.isEmpty()) out.add(s);
                }
            } catch (Exception ignored) {
                // Corrupt list: start clean rather than crash on launch.
            }
        }
        Collections.sort(out, new Comparator<Server>() {
            @Override
            public int compare(Server a, Server b) {
                if (a.lastOpened != b.lastOpened) return Long.compare(b.lastOpened, a.lastOpened);
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    Server get(String id) {
        if (id == null) return null;
        for (Server s : all()) if (s.id.equals(id)) return s;
        return null;
    }

    void save(List<Server> servers) {
        JSONArray arr = new JSONArray();
        for (Server s : servers) {
            try {
                arr.put(s.toJson());
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    Server upsert(Server server) {
        List<Server> list = all();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(server.id)) {
                list.set(i, server);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(server);
        save(list);
        return server;
    }

    void delete(String id) {
        List<Server> list = all();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        save(list);
    }

    void touch(String id) {
        List<Server> list = all();
        for (Server s : list) {
            if (s.id.equals(id)) {
                s.lastOpened = System.currentTimeMillis();
                break;
            }
        }
        save(list);
    }

    boolean isEmpty() {
        return all().isEmpty();
    }
}
