package shop;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
    private final Environment environment;

    public RelayController(
            RestClient.Builder restClient, WebClient.Builder webClient, RestTemplate restTemplate, Environment environment) {
        this.restClient = restClient;
        this.webClient = webClient;
        this.restTemplate = restTemplate;
        this.environment = environment;
    }

    @GetMapping("/relay/{client}")
    public Map<String, Object> relay(@PathVariable String client) {
        String url = "http://127.0.0.1:" + environment.getProperty("local.server.port") + "/shelves/" + client + "/books";
        String books = switch (client) {
            case "rest-client" -> restClient.build().get().uri(url).retrieve().body(String.class);
            case "rest-template" -> restTemplate.getForObject(url, String.class);
            default -> webClient.build().get().uri(url).retrieve().bodyToMono(String.class).block();
        };
        return Map.of("length", String.valueOf(books).length());
    }

    /** A RestTemplate the app makes itself, as a bean. */
    @Configuration
    static class Templates {
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }
    }
}
