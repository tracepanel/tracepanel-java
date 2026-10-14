package shop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A Kotlin controller's suspend function: its queries run on other threads, after it resumes. */
@RestController
class CoroutineController(private val db: JdbcTemplate) {
    @GetMapping("/coroutine")
    suspend fun coroutine(): String {
        val first = withContext(Dispatchers.IO) { db.queryForObject("SELECT 1 AS io", Int::class.java) }
        delay(20)
        val second = db.queryForObject("SELECT 2 AS resumed", Int::class.java)
        return "sum ${first!! + second!!}"
    }
}
