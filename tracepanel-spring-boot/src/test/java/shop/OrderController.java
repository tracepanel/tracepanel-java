package shop;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** What a request carries, and a handler slow enough to profile. */
@RestController
public class OrderController {
    /** A JSON body, read by Spring; the session and a header of the answer. */
    @PostMapping("/orders")
    public Map<String, Object> order(@RequestBody Map<String, Object> order, HttpSession session, HttpServletResponse response) {
        session.setAttribute("cart", "3 books");
        session.setAttribute("csrf_token", "s3cret");
        response.setHeader("X-Order", "17");
        return Map.of("id", 17, "items", order.get("items"));
    }

    /** A form, parsed by the container. */
    @PostMapping("/sign-up")
    public String signUp(@RequestParam String email, @RequestParam String password) {
        return "welcome " + email;
    }

    /** A form with a file. */
    @PostMapping("/covers")
    public String cover(@RequestParam String title, @RequestParam MultipartFile cover) {
        return title + " " + cover.getSize();
    }

    /** A body the handler never reads. */
    @PostMapping("/ignored")
    public String ignored() {
        return "ignored";
    }

    @GetMapping("/report")
    public String report() {
        return "report " + crunch(60);
    }

    @GetMapping("/report/later")
    public Callable<String> reportLater() {
        return () -> "report " + crunch(60);
    }

    /** Busy for about `millis`: where a profile sees the time go. */
    static long crunch(long millis) {
        long until = System.nanoTime() + millis * 1_000_000;
        long sum = 0;
        while (System.nanoTime() < until) sum += Long.numberOfTrailingZeros(System.nanoTime());
        return sum % 10;
    }
}
