package shop;

import dev.tracepanel.core.Tracepanel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** What a call is asked to profile for, as the app it reaches reads it: its X-Tracepanel-Profile, or none. */
    @GetMapping("/asked")
    public String asked(@RequestHeader(name = "X-Tracepanel-Profile", required = false) String asked) {
        return asked == null ? "none" : asked;
    }

    /** A call to /asked through `client`: what the app it reaches was asked; `own` the header the app sets itself. */
    @GetMapping("/relay/{client}/asked")
    public String relayAsked(@PathVariable String client, @RequestParam(required = false) String own) {
        return callAsked(client, own);
    }

    /** The handler profiles its own trace (Tracepanel.profile), then calls /asked: whether it is profiled, and that. */
    @GetMapping("/relay/{client}/profiled")
    public String relayProfiled(@PathVariable String client) {
        return Tracepanel.profile() + " " + callAsked(client, null);
    }

    private String callAsked(String client, String own) {
        String url = "http://127.0.0.1:" + environment.getProperty("local.server.port") + "/asked";
        String[] headers = own == null ? new String[0] : new String[] {"X-Tracepanel-Profile", own};
        return switch (client) {
            case "rest-client" -> restClient.build().get().uri(url).headers(h -> set(h, headers)).retrieve().body(String.class);
            case "rest-template" -> restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(httpHeaders(headers)), String.class).getBody();
            case "jdk" -> {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
                if (headers.length > 0) request.headers(headers);
                try {
                    yield jdk.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            default -> webClient.build().get().uri(url).headers(h -> set(h, headers)).retrieve().bodyToMono(String.class).block();
        };
    }

    private static void set(HttpHeaders target, String[] headers) {
        for (int i = 0; i < headers.length; i += 2) target.set(headers[i], headers[i + 1]);
    }

    private static HttpHeaders httpHeaders(String[] headers) {
        HttpHeaders target = new HttpHeaders();
        set(target, headers);
        return target;
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
