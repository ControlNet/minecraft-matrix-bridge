package space.controlnet.minecraftmatrixbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Localizer {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final String RESOURCE_PREFIX = "assets/minecraftmatrixbridge/lang/";
    private static final ConcurrentHashMap<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private Localizer() {
    }

    public static String connected(String language, String roomIdOrAlias) {
        String room = (roomIdOrAlias == null || roomIdOrAlias.isBlank()) ? "<unknown>" : roomIdOrAlias;
        return format(language, "minecraftmatrixbridge.connected", Map.of("room", room));
    }

    public static String format(String language, String key, Map<String, String> vars) {
        if (key == null || key.isBlank()) {
            return "";
        }

        String lang = normalizeLanguage(language);
        String template = lookup(lang, key);
        if (template == null) {
            template = lookup(DEFAULT_LANGUAGE, key);
        }
        if (template == null) {
            template = key;
        }
        return applyVars(template, vars);
    }

    private static String normalizeLanguage(String language) {
        String s = (language == null) ? "" : language.trim();
        if (s.isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String lookup(String language, String key) {
        Map<String, String> map = CACHE.computeIfAbsent(language, Localizer::loadLanguageFile);
        return map.get(key);
    }

    private static Map<String, String> loadLanguageFile(String language) {
        String path = RESOURCE_PREFIX + language + ".json";
        try (InputStream in = Localizer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return Collections.emptyMap();
            }
            JsonObject obj = parsed.getAsJsonObject();
            HashMap<String, String> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private static String applyVars(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty() || vars == null || vars.isEmpty()) {
            return template == null ? "" : template;
        }
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank()) {
                continue;
            }
            String v = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{" + k + "}", v);
        }
        return out;
    }
}
