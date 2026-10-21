package lib

import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** The library's own thread: nothing of the caller's is on its stack. */
private val shelves = Executors.newSingleThreadExecutor { Thread(it, "shelves").apply { isDaemon = true } }.asCoroutineDispatcher()

/**
 * Library code outside the app's packages that queries on a thread of its own: no code of the app's is on the stack
 * there, as with a library's lazy loading.
 */
suspend fun countBooks(db: DataSource): Int = withContext(shelves) {
    db.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM book").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }
}
