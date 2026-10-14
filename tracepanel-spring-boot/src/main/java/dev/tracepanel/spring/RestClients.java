package dev.tracepanel.spring;

import java.lang.reflect.Method;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * The app's RestClient and RestTemplate calls recorded: each RestClient.Builder and RestTemplateBuilder Spring Boot
 * hands out, and each RestTemplate bean, gets the interceptor once.
 */
final class RestClients implements BeanPostProcessor {
    // Spring Boot 4 moved RestTemplateBuilder to its own module's package: found by its name, either way.
    private static final List<String> TEMPLATE_BUILDERS = List.of(
            "org.springframework.boot.restclient.RestTemplateBuilder", "org.springframework.boot.web.client.RestTemplateBuilder");

    private final TracedRequests interceptor = new TracedRequests();

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof RestClient.Builder builder) {
            return builder.requestInterceptors(list -> add(list));
        }
        if (bean instanceof RestTemplate template) {
            add(template.getInterceptors());
            return bean;
        }
        if (TEMPLATE_BUILDERS.contains(bean.getClass().getName())) return templateBuilder(bean);
        return bean;
    }

    private void add(List<ClientHttpRequestInterceptor> interceptors) {
        if (interceptors.stream().noneMatch(TracedRequests.class::isInstance)) interceptors.add(interceptor);
    }

    /** RestTemplateBuilder is immutable: the bean is the builder with the interceptor added. */
    private Object templateBuilder(Object builder) {
        try {
            Method add = builder.getClass().getMethod("additionalInterceptors", ClientHttpRequestInterceptor[].class);
            return add.invoke(builder, (Object) new ClientHttpRequestInterceptor[] {interceptor});
        } catch (ReflectiveOperationException e) {
            return builder;
        }
    }
}
