package shop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/** Calls to another API, here the app itself: through each of Spring's HTTP clients. */
@RestController
public class RelayController {
    private final RestClient.Builder restClient;
    private final WebClient.Builder webClient;
    private final RestTemplate restTemplate;
    private final HttpClient jdk;
    private final Environment environment;

    public RelayController(RestClient.Builder restClient, WebClient.Builder webClient, RestTemplate restTemplate,
            HttpClient jdk, Environment environment) {
        this.restClient = restClient;
        this.webClient = webClient;
        this.restTemplate = restTemplate;
        this.jdk = jdk;
        this.environment = environment;
    }

    @GetMapping("/relay/{client}")
    public Map<String, Object> relay(@PathVariable String client) {
        String url = "http://127.0.0.1:" + environment.getProperty("local.server.port") + "/shelves/" + client + "/books";
        String books = switch (client) {
            case "rest-client" -> restClient.build().get().uri(url).retrieve().body(String.class);
            case "rest-template" -> restTemplate.getForObject(url, String.class);
            case "jdk" -> send(url);
            case "jdk-async" -> jdk.sendAsync(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
                    .join().body();
            // RestClient over the JDK's client: one call, not one per client.
            case "rest-client-jdk" -> restClient.requestFactory(new JdkClientHttpRequestFactory(jdk)).build()
                    .get().uri(url).retrieve().body(String.class);
            default -> webClient.build().get().uri(url).retrieve().bodyToMono(String.class).block();
        };
        return Map.of("length", String.valueOf(books).length());
    }

    private String send(String url) {
        try {
            return jdk.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A RestTemplate and a JDK HttpClient the app makes itself, as beans. */
    @Configuration
    static class Templates {
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @Bean
        HttpClient httpClient() {
            return HttpClient.newHttpClient();
        }
    }
}
