package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LocalizerTest {
    @Test
    void connectedUsesZhCnWhenAvailable() {
        assertEquals("Matrix 房间已连接: !room:example.com", Localizer.connected("zh_cn", "!room:example.com"));
    }

    @Test
    void connectedFallsBackToEnUs() {
        assertEquals("Matrix room connected: !room:example.com", Localizer.connected("xx_yy", "!room:example.com"));
    }
}
