package shop;

import static dev.tracepanel.junit.TracepanelExtension.maxQueries;
import static dev.tracepanel.junit.TracepanelExtension.testTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tracepanel.junit.TestTrace;
import dev.tracepanel.junit.Tracepanel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the extension runs, through the JUnit Platform's test kit: each class one run's tests. */
final class Examples {
    /** The bodies that ran, by test. */
    static final AtomicInteger RAN = new AtomicInteger();

    private Examples() {}

    @Tag("example")
    @Tracepanel
    static class Passing {
        @Test
        void lists(TestTrace trace) {
            Library.authors();
            assertEquals(1, trace.queries());
            assertEquals(List.of("SELECT name FROM author ORDER BY id"), trace.sql().stream().map(q -> q.statement()).toList());
            assertEquals(trace, testTrace());
        }
    }

    @Tag("example")
    static class Autodetected {
        @Test
        void lists() {
            Library.authors();
            assertEquals(1, testTrace().queries());
        }
    }

    @Tag("example")
    @Tracepanel
    static class TooMany {
        @Test
        @Tracepanel(maxQueries = 2)
        void lists() {
            Library.titlesWithAuthors();
        }
    }

    @Tag("example")
    @Tracepanel
    static class Found {
        @Test
        void nPlusOne() {
            Library.titlesWithAuthors();
        }
    }

    @Tag("example")
    @Tracepanel(allow = "n+1")
    static class Allowed {
        @Test
        void nPlusOne() {
            Library.titlesWithAuthors();
        }

        @Test
        @Tracepanel(failOn = {"n+1", "duplicates"}, allow = "duplicates")
        void nearestWins() {
            Library.titlesWithAuthors();
        }

        @Nested
        class Inner {
            @Test
            void stillAllowed() {
                Library.titlesWithAuthors();
            }
        }
    }

    @Tag("example")
    @Tracepanel(failOn = {})
    static class FailsOnNothing {
        @Test
        void nPlusOne() {
            Library.titlesWithAuthors();
        }
    }

    @Tag("example")
    @Tracepanel
    static class Misspelled {
        @Test
        @Tracepanel(allow = {"n+2", "dupes"})
        void lists() {
            RAN.incrementAndGet();
        }

        @Test
        @Tracepanel(maxQueries = -5)
        void negative() {
            RAN.incrementAndGet();
        }
    }

    @Tag("example")
    @Tracepanel
    static class Helper {
        @Test
        void underTheLimit() {
            List<String> titles = maxQueries(5, Library::titlesWithAuthors);
            assertEquals(4, titles.size());
        }

        @Test
        void overTheLimit() {
            Library.authors();
            maxQueries(1, () -> {
                Library.authors();
                Library.authors();
            });
        }
    }

    @Tag("example")
    @Tracepanel
    static class Outcomes {
        @Test
        void fails() {
            Library.authors();
            throw new IllegalStateException("boom");
        }

        @Test
        void aborted() {
            assumeTrue(false, "not here");
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void each(int page) {
            Library.authors();
        }
    }
}
