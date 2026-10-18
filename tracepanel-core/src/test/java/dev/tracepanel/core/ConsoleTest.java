package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Who the console on the app's origin answers: this machine, unless TRACEPANEL_CONSOLE_REMOTE opens it. */
class ConsoleTest {
    private static final List<String> CLIENTS =
            List.of("127.0.0.1", "127.0.1.1", "::1", "0:0:0:0:0:0:0:1", "localhost", "172.17.0.1", "10.0.0.5", "203.0.113.9");

    private List<Boolean> answered(boolean remote) {
        return CLIENTS.stream().map(from -> Console.answers(from, remote)).toList();
    }

    @Test
    void this_machine_only_by_default() {
        assertEquals(List.of(true, true, true, true, true, false, false, false), answered(false));
        assertEquals(false, Console.answers(null, false));
    }

    @Test
    void every_client_once_opened() {
        assertEquals(List.of(true, true, true, true, true, true, true, true), answered(true));
    }

    @Test
    void the_variable_opens_it() {
        assertEquals(false, Config.load(Map.of(), "shop").consoleRemote());
        assertEquals(true, Config.load(Map.of("TRACEPANEL_CONSOLE_REMOTE", "1"), "shop").consoleRemote());
        assertEquals(false, Config.load(Map.of("TRACEPANEL_CONSOLE_REMOTE", "0"), "shop").consoleRemote());
    }
}
