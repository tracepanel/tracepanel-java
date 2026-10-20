package dev.tracepanel.integrations;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Log records made while a trace runs, in that trace: level, logger, message, and the app's line that logged it. */
public final class LogbackRecorder extends AppenderBase<ILoggingEvent> {
    private final Tracepanel tracepanel;

    LogbackRecorder(Tracepanel tracepanel) {
        this.tracepanel = tracepanel;
    }

    /** On the root logger of Logback (Spring Boot's and Ktor's default), when Logback is the one logging. */
    public static void install(Tracepanel tracepanel) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return;
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        // Once per process: a second app that installs it (two Ktor servers) would record each log twice.
        if (root.getAppender("tracepanel") != null) return;
        LogbackRecorder appender = new LogbackRecorder(tracepanel);
        appender.setContext(context);
        appender.setName("tracepanel");
        appender.start();
        root.addAppender(appender);
    }

    @Override
    protected void append(ILoggingEvent event) {
        Trace trace = Tracepanel.current();
        if (trace == null) return;
        Map<String, Object> attrs = new LinkedHashMap<>();
        StackTraceElement[] caller = event.getCallerData();
        if (caller != null && caller.length > 0) {
            StackTraceElement at = caller[0];
            attrs.put("code.filepath", tracepanel.stacks().path(at.getClassName(), at.getFileName()));
            attrs.put("code.lineno", at.getLineNumber());
            attrs.put("code.function", at.getMethodName());
        }
        attrs.put("thread.name", event.getThreadName());
        attrs.put("log.template", event.getMessage());
        IThrowableProxy thrown = event.getThrowableProxy();
        trace.log(level(event.getLevel()), event.getLoggerName(), event.getFormattedMessage(),
                thrown == null ? null : ThrowableProxyUtil.asString(thrown), attrs);
    }

    private static String level(Level level) {
        if (level.isGreaterOrEqual(Level.ERROR)) return "error";
        if (level.isGreaterOrEqual(Level.WARN)) return "warning";
        if (level.isGreaterOrEqual(Level.INFO)) return "info";
        return "debug";
    }
}
