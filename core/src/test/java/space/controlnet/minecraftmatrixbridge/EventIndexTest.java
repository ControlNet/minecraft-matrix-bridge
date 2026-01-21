package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class EventIndexTest {

    private EventIndex eventIndex;

    @BeforeEach
    void setUp() {
        Map<String, List<String>> bySimpleName = new HashMap<>();
        bySimpleName.put("ServerChatEvent", List.of("net.minecraftforge.event.ServerChatEvent"));
        bySimpleName.put("BlockEvent", List.of(
                "net.minecraftforge.event.level.BlockEvent",
                "net.minecraftforge.event.world.BlockEvent"
        ));
        bySimpleName.put("ServerTickEvent", List.of("net.minecraftforge.event.TickEvent$ServerTickEvent"));

        Map<String, List<String>> byAlias = new HashMap<>();
        byAlias.put("TickEvent$ServerTickEvent", List.of("net.minecraftforge.event.TickEvent$ServerTickEvent"));
        byAlias.put("TickEvent.ServerTickEvent", List.of("net.minecraftforge.event.TickEvent$ServerTickEvent"));
        byAlias.put("PlayerEvent$PlayerLoggedInEvent", List.of("net.minecraftforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent"));

        eventIndex = EventIndex.fromMaps(bySimpleName, byAlias, "forge-1.20");
    }

    @Test
    void fromMapsCreatesCorrectIndex() {
        assertEquals("forge-1.20", eventIndex.getLoader());
        assertEquals(3, eventIndex.getSimpleNameCount());
        assertEquals(3, eventIndex.getAliasCount());
        assertFalse(eventIndex.isEmpty());
    }

    @Test
    void emptyIndexIsEmpty() {
        EventIndex empty = EventIndex.empty();
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.getSimpleNameCount());
        assertEquals(0, empty.getAliasCount());
    }

    @Test
    void resolveSimpleNameSuccess() {
        ResolveResult result = eventIndex.resolve("ServerChatEvent");
        assertTrue(result.isSuccess());
        assertEquals("net.minecraftforge.event.ServerChatEvent", result.getFqcn());
    }

    @Test
    void resolveSimpleNameAmbiguous() {
        ResolveResult result = eventIndex.resolve("BlockEvent");
        assertTrue(result.isAmbiguous());
        List<String> candidates = result.getCandidates();
        assertEquals(2, candidates.size());
        assertTrue(candidates.contains("net.minecraftforge.event.level.BlockEvent"));
        assertTrue(candidates.contains("net.minecraftforge.event.world.BlockEvent"));
    }

    @Test
    void resolveSimpleNameNotFound() {
        ResolveResult result = eventIndex.resolve("NonExistentEvent");
        assertTrue(result.isNotFound());
    }

    @Test
    void resolveAliasDollarNotation() {
        ResolveResult result = eventIndex.resolve("TickEvent$ServerTickEvent");
        assertTrue(result.isSuccess());
        assertEquals("net.minecraftforge.event.TickEvent$ServerTickEvent", result.getFqcn());
    }

    @Test
    void resolveAliasDotNotation() {
        ResolveResult result = eventIndex.resolve("TickEvent.ServerTickEvent");
        assertTrue(result.isSuccess());
        assertEquals("net.minecraftforge.event.TickEvent$ServerTickEvent", result.getFqcn());
    }

    @Test
    void resolveFqcnPassesThrough() {
        ResolveResult result = eventIndex.resolve("net.minecraftforge.event.ServerChatEvent");
        assertTrue(result.isSuccess());
        assertEquals("net.minecraftforge.event.ServerChatEvent", result.getFqcn());
    }

    @Test
    void resolveFqcnPassesThroughEvenIfNotInIndex() {
        ResolveResult result = eventIndex.resolve("com.example.CustomEvent");
        assertTrue(result.isSuccess());
        assertEquals("com.example.CustomEvent", result.getFqcn());
    }

    @Test
    void resolveNullReturnsNotFound() {
        ResolveResult result = eventIndex.resolve(null);
        assertTrue(result.isNotFound());
    }

    @Test
    void resolveEmptyReturnsNotFound() {
        ResolveResult result = eventIndex.resolve("");
        assertTrue(result.isNotFound());
    }

    @Test
    void resolveBlankReturnsNotFound() {
        ResolveResult result = eventIndex.resolve("   ");
        assertTrue(result.isNotFound());
    }

    @Test
    void resolveTrimsWhitespace() {
        ResolveResult result = eventIndex.resolve("  ServerChatEvent  ");
        assertTrue(result.isSuccess());
        assertEquals("net.minecraftforge.event.ServerChatEvent", result.getFqcn());
    }

    @Test
    void searchFindsMatchingSimpleNames() {
        List<String> results = eventIndex.search("Chat", 10);
        assertFalse(results.isEmpty());
        assertTrue(results.contains("net.minecraftforge.event.ServerChatEvent"));
    }

    @Test
    void searchCaseInsensitive() {
        List<String> results = eventIndex.search("chat", 10);
        assertFalse(results.isEmpty());
        assertTrue(results.contains("net.minecraftforge.event.ServerChatEvent"));
    }

    @Test
    void searchRespectsLimit() {
        List<String> results = eventIndex.search("Event", 1);
        assertEquals(1, results.size());
    }

    @Test
    void searchNullReturnsEmpty() {
        List<String> results = eventIndex.search(null, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchEmptyReturnsEmpty() {
        List<String> results = eventIndex.search("", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchZeroLimitReturnsEmpty() {
        List<String> results = eventIndex.search("Chat", 0);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchNegativeLimitReturnsEmpty() {
        List<String> results = eventIndex.search("Chat", -1);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchFindsByFqcn() {
        List<String> results = eventIndex.search("minecraftforge", 10);
        assertFalse(results.isEmpty());
    }

    @Test
    void resolveResultSuccessAccessors() {
        ResolveResult.Success success = ResolveResult.success("com.example.Event");
        assertTrue(success.isSuccess());
        assertFalse(success.isAmbiguous());
        assertFalse(success.isNotFound());
        assertEquals("com.example.Event", success.fqcn());
        assertEquals("com.example.Event", success.getFqcn());
    }

    @Test
    void resolveResultAmbiguousAccessors() {
        ResolveResult.Ambiguous ambiguous = ResolveResult.ambiguous(List.of("a", "b"));
        assertFalse(ambiguous.isSuccess());
        assertTrue(ambiguous.isAmbiguous());
        assertFalse(ambiguous.isNotFound());
        assertEquals(List.of("a", "b"), ambiguous.candidates());
        assertEquals(List.of("a", "b"), ambiguous.getCandidates());
    }

    @Test
    void resolveResultNotFoundAccessors() {
        ResolveResult.NotFound notFound = ResolveResult.notFound();
        assertFalse(notFound.isSuccess());
        assertFalse(notFound.isAmbiguous());
        assertTrue(notFound.isNotFound());
    }

    @Test
    void resolveResultSuccessThrowsOnGetCandidates() {
        ResolveResult.Success success = ResolveResult.success("com.example.Event");
        assertThrows(IllegalStateException.class, success::getCandidates);
    }

    @Test
    void resolveResultAmbiguousThrowsOnGetFqcn() {
        ResolveResult.Ambiguous ambiguous = ResolveResult.ambiguous(List.of("a", "b"));
        assertThrows(IllegalStateException.class, ambiguous::getFqcn);
    }

    @Test
    void resolveResultNotFoundThrowsOnGetFqcn() {
        ResolveResult.NotFound notFound = ResolveResult.notFound();
        assertThrows(IllegalStateException.class, notFound::getFqcn);
    }

    @Test
    void resolveResultNotFoundThrowsOnGetCandidates() {
        ResolveResult.NotFound notFound = ResolveResult.notFound();
        assertThrows(IllegalStateException.class, notFound::getCandidates);
    }

    @Test
    void resolveResultEquality() {
        assertEquals(ResolveResult.success("a"), ResolveResult.success("a"));
        assertNotEquals(ResolveResult.success("a"), ResolveResult.success("b"));
        assertEquals(ResolveResult.ambiguous(List.of("a", "b")), ResolveResult.ambiguous(List.of("a", "b")));
        assertEquals(ResolveResult.notFound(), ResolveResult.notFound());
        assertNotEquals(ResolveResult.success("a"), ResolveResult.notFound());
    }

    @Test
    void resolveResultHashCode() {
        assertEquals(ResolveResult.success("a").hashCode(), ResolveResult.success("a").hashCode());
        assertEquals(ResolveResult.notFound().hashCode(), ResolveResult.notFound().hashCode());
    }

    @Test
    void resolveResultToString() {
        assertTrue(ResolveResult.success("a").toString().contains("a"));
        assertTrue(ResolveResult.ambiguous(List.of("a", "b")).toString().contains("a"));
        assertTrue(ResolveResult.notFound().toString().contains("NotFound"));
    }
}
