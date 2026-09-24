package com.simple.videoeditor;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Android commit-to-memory precedes disk I/O, including when commit returns false. */
final class FaultPublicationPreferences {
    final Map<String, Object> memory = new HashMap<>();
    final Map<String, Object> durable = new HashMap<>();
    final SharedPreferences prefs;
    boolean failFinal, keepFailing, failed, mutatedOnFailure;
    int failAt, commits;

    FaultPublicationPreferences(SharedPreferences initial) {
        memory.putAll(initial.getAll());
        memory.put("sentinel", "unrelated");
        durable.putAll(memory);
        prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("edit".equals(name)) return editor();
                    if ("getAll".equals(name)) return new HashMap<>(memory);
                    if ("contains".equals(name)) return memory.containsKey(args[0]);
                    if (name.startsWith("get")) return memory.getOrDefault(args[0], args[1]);
                    if (name.endsWith("OnSharedPreferenceChangeListener")) return null;
                    throw new UnsupportedOperationException(name);
                });
    }

    void restart() {
        memory.clear();
        memory.putAll(durable);
        failFinal = keepFailing = failed = false;
        failAt = commits = 0;
    }

    private SharedPreferences.Editor editor() {
        Map<String, Object> changes = new HashMap<>();
        boolean[] clear = {false};
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) {
                        changes.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if ("remove".equals(name)) {
                        changes.put((String) args[0], null);
                        return proxy;
                    }
                    if ("clear".equals(name)) {
                        clear[0] = true;
                        return proxy;
                    }
                    if ("commit".equals(name) || "apply".equals(name)) {
                        if (clear[0]) memory.clear();
                        for (Map.Entry<String, Object> entry : changes.entrySet()) {
                            if (entry.getValue() == null) memory.remove(entry.getKey());
                            else memory.put(entry.getKey(), entry.getValue());
                        }
                        boolean reject = (++commits == failAt)
                                || (failFinal && !failed && changes.containsKey("uri")
                                && changes.containsKey("pending") && changes.get("pending") == null)
                                || (failed && keepFailing);
                        if (reject) {
                            failed = true;
                            mutatedOnFailure = true;
                        } else {
                            durable.clear();
                            durable.putAll(memory);
                        }
                        return "apply".equals(name) ? null : !reject;
                    }
                    throw new UnsupportedOperationException(name);
                });
    }
}
