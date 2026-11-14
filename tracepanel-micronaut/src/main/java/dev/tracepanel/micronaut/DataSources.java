package dev.tracepanel.micronaut;

import dev.tracepanel.core.Jdbc;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requires(condition = TracepanelEnabled.class)
final class DataSources implements BeanCreatedEventListener<DataSource> {
    private static final Logger LOG = LoggerFactory.getLogger(DataSources.class);

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
