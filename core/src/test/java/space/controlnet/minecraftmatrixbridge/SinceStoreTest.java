package space.controlnet.minecraftmatrixbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SinceStoreTest {

    @TempDir
    Path worldRoot;

    @Test
    void saveSinceCreatesStateFile() {
        SinceStore store = new SinceStore(worldRoot);

        store.saveSince("s12345");

        Path statePath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json");
        assertTrue(Files.exists(statePath));

        String loaded = store.loadSince();
        assertEquals("s12345", loaded);
    }

    @Test
    void loadSinceReturnsEmptyWhenNoFile() {
        SinceStore store = new SinceStore(worldRoot);

        String since = store.loadSince();

        assertEquals("", since);
    }

    @Test
    void saveSincePreservesOtherFields() throws Exception {
        SinceStore store = new SinceStore(worldRoot);

        store.saveSince("s1");
        store.saveSelfUserId("@bot:example.com");
        store.saveSince("s2");

        Path statePath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json");
        String json = Files.readString(statePath, StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("s2", obj.get("since").getAsString());
        assertEquals("@bot:example.com", obj.get("self_user_id").getAsString());
        assertTrue(obj.has("updated_at_ms"));
    }

    @Test
    void atomicWriteDoesNotLeaveTmpFile() {
        SinceStore store = new SinceStore(worldRoot);

        store.saveSince("s1");
        store.saveSince("s2");
        store.saveSince("s3");

        Path tmpPath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json.tmp");
        assertFalse(Files.exists(tmpPath), "temp file should be removed after atomic write");

        Path statePath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json");
        assertTrue(Files.exists(statePath));
        assertEquals("s3", store.loadSince());
    }

    @Test
    void saveSinceHandlesNullAndBlank() {
        SinceStore store = new SinceStore(worldRoot);

        store.saveSince("s1");
        assertEquals("s1", store.loadSince());

        store.saveSince(null);
        assertEquals("", store.loadSince());

        store.saveSince("s2");
        assertEquals("s2", store.loadSince());

        store.saveSince("");
        assertEquals("", store.loadSince());
    }

    @Test
    void saveSelfUserIdIgnoresNullAndBlank() throws Exception {
        SinceStore store = new SinceStore(worldRoot);

        store.saveSelfUserId(null);
        Path statePath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json");
        assertFalse(Files.exists(statePath));

        store.saveSelfUserId("");
        assertFalse(Files.exists(statePath));

        store.saveSelfUserId("@bot:example.com");
        assertTrue(Files.exists(statePath));
        String json = Files.readString(statePath, StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("@bot:example.com", obj.get("self_user_id").getAsString());
    }
}
