package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveTasksTest {
    private static final Pattern EVENT = Pattern.compile("\\{\"event\":[^{}]*\\}");

    /** A sender to `agent`, as the app's. */
    private static Sender sender(Path root, SenderTest.OneAgent agent) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        Files.writeString(store.resolve("agent.addr"), agent.address() + "\n");
        Map<String, String> env = Map.of("TRACEPANEL_ROOT", root.toString(), "TRACEPANEL_DIR", store.toString());
        return new Sender(Config.load(env, "shop"), new Agent(store, env));
    }

    /** The task events the agent got, in their order, each as its JSON. */
    private static List<String> events(SenderTest.OneAgent agent, int count) throws InterruptedException {
        for (int waited = 0; waited < 5000; waited += 20) {
            List<String> events = agent.frames.stream()
                    .filter(frame -> frame.startsWith("{\"tasks\":["))
                    .flatMap(frame -> EVENT.matcher(frame).results().map(java.util.regex.MatchResult::group))
                    .toList();
            if (events.size() >= count) return events;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("fewer than " + count + " task events: " + agent.frames);
    }

    private static String time(String event) {
        Matcher time = Pattern.compile("\"time\":(\\d+)").matcher(event);
        assertTrue(time.find(), event);
        return time.group(1);
    }

    @Test
    void a_task_s_events_go_in_frames_of_their_own_from_queued_to_finished(@TempDir Path root) throws Exception {
        try (SenderTest.OneAgent agent = new SenderTest.OneAgent()) {
            LiveTasks tasks = new LiveTasks(sender(root, agent), "shop");
            String worker = tasks.worker();

            tasks.enqueued("kafka", "t1", "books", "books", "TRACE", "SPAN");
            tasks.started("kafka", "t1", "books", "books", 0);
            tasks.named("t1", "Listeners.onBook");
            tasks.finished("kafka", "t1", "SUCCESS");
            List<String> events = events(agent, 5);

            assertTrue(worker.startsWith("shop@") && worker.length() > "shop@".length(), worker);

            assertEquals(List.of(
                    "{\"event\":\"enqueued\",\"system\":\"kafka\",\"id\":\"t1\",\"time\":0,\"name\":\"books\",\"queue\":\"books\","
                            + "\"trace_id\":\"TRACE\",\"span_id\":\"SPAN\"}",
                    // The first task starts the worker's heartbeat, with the task it runs.
                    "{\"event\":\"heartbeat\",\"system\":\"kafka\",\"id\":\"\",\"time\":0,\"worker\":\"" + worker + "\",\"ids\":[\"t1\"]}",
                    "{\"event\":\"started\",\"system\":\"kafka\",\"id\":\"t1\",\"time\":0,\"name\":\"books\",\"queue\":\"books\","
                            + "\"worker\":\"" + worker + "\",\"retries\":0}",
                    "{\"event\":\"started\",\"system\":\"kafka\",\"id\":\"t1\",\"time\":0,\"name\":\"Listeners.onBook\",\"queue\":\"books\","
                            + "\"worker\":\"" + worker + "\",\"retries\":0}",
                    "{\"event\":\"finished\",\"system\":\"kafka\",\"id\":\"t1\",\"time\":0,\"worker\":\"" + worker + "\",\"state\":\"SUCCESS\"}"),
                    events.stream().map(event -> event.replaceAll("\"time\":\\d+", "\"time\":0")).toList());

            // Named once its listener is known, it started when it did.
            assertEquals(time(events.get(2)), time(events.get(3)));

            // The hello said the app sends them.
            assertTrue(agent.hellos.stream().anyMatch(hello -> hello.contains("\"live_tasks\"")), agent.hellos.toString());
        }
    }

    @Test
    void a_connection_made_before_the_app_sent_something_new_says_it_again(@TempDir Path root) throws Exception {
        try (SenderTest.OneAgent agent = new SenderTest.OneAgent()) {
            Sender sender = sender(root, agent);
            String name = "test_" + Ulid.next().toLowerCase(java.util.Locale.ROOT);
            sender.submit(Map.of("id", "before"));
            assertTrue(agent.got("before"));

            Sender.sends(name);
            sender.submit(Map.of("id", "after"));

            assertTrue(agent.got("after"));

            assertEquals(2, agent.hellos.size());
            assertTrue(!agent.hellos.get(0).contains(name), agent.hellos.get(0));
            assertTrue(agent.hellos.get(1).contains("\"" + name + "\""), agent.hellos.get(1));
        }
    }
}
