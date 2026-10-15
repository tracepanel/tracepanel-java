package dev.tracepanel.spring;

import dev.tracepanel.core.Jdbc;
import java.sql.Connection;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Each DataSource bean's connections recorded (Jdbc), the bean kept as its own class (HikariDataSource stays one),
 * whatever uses it: JPA, Hibernate, Spring Data, JdbcTemplate, jOOQ.
 */
final class DataSources implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource)) return bean;
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        String alias = name.equals("dataSource") ? "default" : name;
        // The bean itself, unrecorded: the console's EXPLAIN runs there.
        Jdbc.register((DataSource) bean, alias);
        factory.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            return result instanceof Connection connection && invocation.getMethod().getName().equals("getConnection")
                    ? Jdbc.wrap(connection, alias)
                    : result;
        });
        return factory.getProxy(bean.getClass().getClassLoader());
    }
}
