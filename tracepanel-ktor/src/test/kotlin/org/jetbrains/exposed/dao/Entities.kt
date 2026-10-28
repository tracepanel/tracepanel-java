package org.jetbrains.exposed.dao

import javax.sql.DataSource

/** In Exposed's packages: a query Exposed's DAO makes, as it reads an entity by its key. */
fun findBook(db: DataSource, id: Int): String = db.connection.use { connection ->
    connection.prepareStatement("SELECT BOOK.TITLE FROM BOOK WHERE BOOK.ID = ?").use { statement ->
        statement.setInt(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "?" }
    }
}
