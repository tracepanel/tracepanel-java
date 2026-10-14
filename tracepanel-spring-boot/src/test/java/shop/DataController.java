package shop;

import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reads from MongoDB and Redis, one per item: an N+1 in each. */
@RestController
public class DataController {
    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;

    public DataController(MongoTemplate mongo, StringRedisTemplate redis) {
        this.mongo = mongo;
        this.redis = redis;
    }

    @GetMapping("/data/mongo")
    public int mongo() {
        mongo.dropCollection("authors");
        mongo.insert(List.of(new Document("_id", 1).append("name", "Ann"), new Document("_id", 2).append("name", "Bob")), "authors");
        int found = 0;
        for (int id : List.of(1, 2, 1, 2)) {
            found += mongo.find(Query.query(Criteria.where("_id").is(id)), Document.class, "authors").size();
        }
        return found;
    }

    @GetMapping("/data/redis")
    public Map<String, Object> redis() {
        for (int id = 1; id <= 4; id++) redis.opsForValue().set("book:" + id, "Book " + id);
        int found = 0;
        for (int id = 1; id <= 4; id++) found += redis.opsForValue().get("book:" + id) == null ? 0 : 1;
        return Map.of("found", found);
    }
}
