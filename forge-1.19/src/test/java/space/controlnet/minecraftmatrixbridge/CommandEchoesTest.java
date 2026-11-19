package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandEchoesTest {
    @Test
    void theMessageOfACommandIsRecognizedOnce() {
        CommandEchoes echoes = new CommandEchoes();
        echoes.noteCommand("msg Bob meet me at spawn", 1_000);
        assertTrue(echoes.consume("meet me at spawn", 1_200));
        // The same words typed into the chat box afterwards are ordinary chat.
        assertFalse(echoes.consume("meet me at spawn", 1_300));
    }

    @Test
    void everyMessageCommandShapeMatches() {
        CommandEchoes echoes = new CommandEchoes();
        echoes.noteCommand("teammsg plan B", 0);
        echoes.noteCommand("me waves", 0);
        echoes.noteCommand("execute as Steve run say hello all", 0);
        assertTrue(echoes.consume("plan B", 10));
        assertTrue(echoes.consume("waves", 10));
        assertTrue(echoes.consume("hello all", 10));
    }

    @Test
    void ordinaryChatIsNotTakenForACommand() {
        CommandEchoes echoes = new CommandEchoes();
        assertFalse(echoes.consume("hello", 0));
        echoes.noteCommand("gamemode creative", 0);
        assertFalse(echoes.consume("hello", 10));
        assertFalse(echoes.consume("creative mode", 10), "only a command's trailing text matches");
    }

    @Test
    void oldCommandsExpire() {
        CommandEchoes echoes = new CommandEchoes();
        echoes.noteCommand("msg Bob hi", 0);
        assertFalse(echoes.consume("hi", CommandEchoes.WINDOW_MS + 1));
    }
}
