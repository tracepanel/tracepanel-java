package dev.tracepanel.micronaut;

import dev.tracepanel.core.Jdbc;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The app's DataSources, each wrapped as it is made: SQL, transactions and EXPLAIN on its connections. A bean whose
 * type is a pool class can't take the wrapper: it stays as it is, and the log says how to have it recorded.
 */
@Singleton
@Requires(condition = TracepanelEnabled.class)
final class DataSources implements BeanCreatedEventListener<DataSource>, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(DataSources.class);

    // First: the pool itself is wrapped, and Micronaut Data's contextual DataSource goes over it. Over that proxy, its
    // transaction manager would hold one connection and the proxy look for another (NoConnectionException).
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public DataSource onCreated(BeanCreatedEvent<DataSource> event) {
        DataSource original = event.getBean();
        String alias = event.getBeanIdentifier().getName();
        DataSource wrapped = Jdbc.dataSource(original, alias);
        if (event.getBeanDefinition().getBeanType().isInstance(wrapped)) return wrapped;
        LOG.warn("Tracepanel cannot wrap concrete DataSource bean {}. Declare the factory return type as DataSource.", alias);
        return original;
    }
}
