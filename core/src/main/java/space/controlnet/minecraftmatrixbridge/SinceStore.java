package space.controlnet.minecraftmatrixbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SinceStore {
    private static final Logger LOGGER = Logger.getLogger("MatrixBridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path statePath;

    public SinceStore(Path worldRoot) {
        this.statePath = worldRoot
                .resolve("data")
                .resolve("minecraftmatrixbridge")
                .resolve("state.json");
    }

    public synchronized String loadSince() {
        JsonObject state = loadStateObject();
        JsonElement since = state.get("since");
        return (since != null && since.isJsonPrimitive()) ? since.getAsString() : "";
    }

    public synchronized void saveSince(String since) {
        JsonObject state = loadStateObject();
        if (since == null || since.isBlank()) {
            state.remove("since");
        } else {
            state.addProperty("since", since);
        }
        atomicWrite(state);
    }

    /**
     * Ties the stored since token to {@code owner} (homeserver and account).
     *
     * <p>A since token is only meaningful to the homeserver and account that issued it.
     * When the stored owner differs, the token is discarded so the next sync starts fresh
     * instead of replaying or skipping events. State written before owners were recorded
     * is adopted by the first owner that binds it.</p>
     *
     * @return {@code true} when the stored since token (if any) was kept
     */
    public synchronized boolean bindOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return true;
        }
        JsonObject state = loadStateObject();
        JsonElement stored = state.get("owner");
        boolean kept = stored == null || !stored.isJsonPrimitive() || owner.equals(stored.getAsString());
        if (!kept) {
            LOGGER.info("MatrixBridge homeserver or account changed; discarding the stored sync token");
            state.remove("since");
        }
        if (!kept || stored == null || !owner.equals(stored.getAsString())) {
            state.addProperty("owner", owner);
            atomicWrite(state);
        }
        return kept;
    }

    public synchronized void saveSelfUserId(String selfUserId) {
        if (selfUserId == null || selfUserId.isBlank()) {
            return;
        }
        JsonObject state = loadStateObject();
        state.addProperty("self_user_id", selfUserId);
        state.addProperty("updated_at_ms", System.currentTimeMillis());
        atomicWrite(state);
    }

    private JsonObject loadStateObject() {
        if (!Files.exists(statePath)) {
            return new JsonObject();
        }
        try {
            String json = Files.readString(statePath, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MatrixBridge failed to read state file " + statePath + ": " + e);
        }
        return new JsonObject();
    }

    private void atomicWrite(JsonObject state) {
        try {
            Files.createDirectories(statePath.getParent());
            Path tmpPath = statePath.resolveSibling("state.json.tmp");
            Files.writeString(tmpPath, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(tmpPath, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmpPath, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MatrixBridge failed to write state file " + statePath + ": " + e);
        }
    }
}
