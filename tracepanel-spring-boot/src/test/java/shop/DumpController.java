package shop;

import dev.tracepanel.core.Tracepanel;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A handler that dumps what it works with, to look at in the trace. */
@RestController
public class DumpController {
    public record Line(String sku, int quantity) {}

    public record Basket(int id, BigDecimal total, List<Line> lines) {}

    @GetMapping("/baskets/{id}")
    public String basket(@PathVariable int id) {
        Basket basket = new Basket(id, new BigDecimal("12.50"), List.of(new Line("pen", 2), new Line("ink", 1)));
        List<String> skus = basket.lines().stream().map(Line::sku).toList();
        Map<String, Integer> stock = new LinkedHashMap<>();
        stock.put("pen", 40);
        stock.put("ink", 3);
        Tracepanel.dump(basket, skus, stock, "checked");
        return "basket " + id;
    }
}
