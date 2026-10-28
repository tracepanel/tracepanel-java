package shop;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class Hello {
    @GetMapping(path = "/hello", produces = MediaType.TEXT_HTML_VALUE)
    String hello() {
        return "<html><head><title>Hello</title></head><body>Hello</body></html>";
    }
}
