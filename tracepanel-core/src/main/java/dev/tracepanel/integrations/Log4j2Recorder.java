package dev.tracepanel.integrations;

import dev.tracepanel.core.Stacks;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.Message;

/**
 * Log records made while a trace runs, in that trace, as LogbackRecorder records Logback's: level, logger, message,
 * the app's line that logged it, the MDC (Log4j's ThreadContext) and the exception. It sees what reaches the root
 * logger on the thread that logged: an async logger hands its records to another thread, outside any trace.
 */
public final class Log4j2Recorder extends AbstractAppender {
    private static final String NAME = "tracepanel";

    private final Stacks stacks;

    Log4j2Recorder(Stacks stacks) {
        super(NAME, null, null, true, Property.EMPTY_ARRAY);
        this.stacks = stacks;
    }

    /** On the root logger of Log4j 2 (spring-boot-starter-log4j2's), when Log4j 2 is the one logging. */
    public static void install(Tracepanel tracepanel) {
        install(tracepanel.stacks());
    }

    static synchronized void install(Stacks stacks) {
        // log4j-api alone, or routed to SLF4J (log4j-to-slf4j, Spring Boot's default): Log4j 2 isn't the one logging.
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) return;
        // Once per process: a second app that installs it (two Ktor servers) would record each log twice.
        if (!addTo(context.getConfiguration(), stacks)) return;
        context.updateLoggers();
        // A configuration that replaces this one (Spring Boot's own, a file that changed) gets it too.
        context.addPropertyChangeListener(event -> {
            if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                    && event.getNewValue() instanceof Configuration next && addTo(next, stacks)) {
                context.updateLoggers(next);
            }
        });
    }

    /** A new appender for each configuration: the one a configuration had stops with it. */
    private static boolean addTo(Configuration config, Stacks stacks) {
        LoggerConfig root = config.getRootLogger();
        if (root.getAppenders().containsKey(NAME)) return false;
        Log4j2Recorder appender = new Log4j2Recorder(stacks);
        appender.start();
        config.addAppender(appender);
        root.addAppender(appender, null, null);
        return true;
    }

    @Override
    public void append(LogEvent event) {
        Trace trace = Tracepanel.current();
        if (trace == null) return;
        Map<String, Object> attrs = new LinkedHashMap<>();
        StackTraceElement at = event.getSource();
        if (at != null) {
            attrs.put("code.filepath", stacks.path(at.getClassName(), at.getFileName()));
            attrs.put("code.lineno", at.getLineNumber());
            attrs.put("code.function", at.getMethodName());
        }
        attrs.put("thread.name", event.getThreadName());
        Message message = event.getMessage();
        attrs.put("log.template", template(message));
        LogFields.put(attrs, event.getContextData().toMap());
        Throwable thrown = event.getThrown();
        trace.log(level(event.getLevel()), event.getLoggerName(), message.getFormattedMessage(),
                thrown == null ? null : stackTrace(thrown), attrs);
    }

    /** "placed {}": the message before its parameters went in. Message.getFormat() is deprecated, not dropped. */
    @SuppressWarnings("deprecation")
    private static String template(Message message) {
        String format = message.getFormat();
        return format == null ? message.getFormattedMessage() : format;
    }

    private static String stackTrace(Throwable thrown) {
        StringWriter out = new StringWriter();
        thrown.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static String level(Level level) {
        if (level.isMoreSpecificThan(Level.FATAL)) return "critical";
        if (level.isMoreSpecificThan(Level.ERROR)) return "error";
        if (level.isMoreSpecificThan(Level.WARN)) return "warning";
        if (level.isMoreSpecificThan(Level.INFO)) return "info";
        return "debug";
    }
}
