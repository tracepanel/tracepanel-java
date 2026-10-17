package shop;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A proxied bean (one of its methods is @Transactional) that hands work to the application's executor and waits for
 * it: the work is the request's own, not an @Async task.
 */
@Service
public class QuoteService {
    private final RestClient.Builder restClient;
    private final Executor executor;
    private final Environment environment;

    public QuoteService(RestClient.Builder restClient, @Qualifier("applicationTaskExecutor") Executor executor,
            Environment environment) {
        this.restClient = restClient;
        this.executor = executor;
        this.environment = environment;
    }

    public String quote() {
        String url = "http://127.0.0.1:" + environment.getProperty("local.server.port") + "/shelves/quote/books";
        return CompletableFuture.supplyAsync(() -> restClient.build().get().uri(url).retrieve().body(String.class), executor)
                .join();
    }

    @Transactional
    public void save() {}

    @RestController
    static class Quotes {
        private final QuoteService quotes;

        Quotes(QuoteService quotes) {
            this.quotes = quotes;
        }

        @GetMapping("/quote")
        String quote() {
            return String.valueOf(quotes.quote().length());
        }
    }
}
