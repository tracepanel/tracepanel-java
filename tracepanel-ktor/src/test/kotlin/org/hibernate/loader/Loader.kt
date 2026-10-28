package org.hibernate.loader

import javax.sql.DataSource

/** In Hibernate's packages: a query Hibernate makes, as it loads an entity by its key. */
fun loadBook(db: DataSource, id: Int): String = db.connection.use { connection ->
    connection.prepareStatement("select b1_0.title from book b1_0 where b1_0.id=?").use { statement ->
        statement.setInt(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "?" }
    }
}
