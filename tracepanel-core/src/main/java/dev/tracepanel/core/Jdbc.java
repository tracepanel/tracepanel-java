package dev.tracepanel.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL through JDBC, whatever runs it (JPA, Hibernate, Spring Data, jOOQ, JdbcTemplate): each statement a span of the
 * current trace with its text, fingerprint, parameters, rows and the app's line that ran it, and each transaction
 * one around its statements.
 */
public final class Jdbc {
    private static final Set<String> EXECUTE = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");
    private static final Set<String> BATCH = Set.of("executeBatch", "executeLargeBatch");

    private Jdbc() {}

    /**
     * A DataSource whose connections are recorded: for an app with no framework integration to find it (Ktor with
     * Exposed or plain JDBC), Jdbc.dataSource(hikari, "default").
     */
    public static javax.sql.DataSource dataSource(javax.sql.DataSource dataSource, String alias) {
        if (Proxy.isProxyClass(dataSource.getClass()) && Proxy.getInvocationHandler(dataSource) instanceof DataSources) return dataSource;
        return (javax.sql.DataSource) Proxy.newProxyInstance(Jdbc.class.getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class}, new DataSources(dataSource, alias));
    }

    private record DataSources(javax.sql.DataSource target, String alias) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = call(target, method, args);
            return result instanceof Connection connection && method.getName().equals("getConnection") ? wrap(connection, alias) : result;
        }
    }

    /** A connection whose statements are recorded, in whichever trace is current when they run. */
    public static Connection wrap(Connection connection, String alias) {
        if (Proxy.isProxyClass(connection.getClass()) && Proxy.getInvocationHandler(connection) instanceof Connections) {
            return connection;
        }
        return (Connection) Proxy.newProxyInstance(
                Jdbc.class.getClassLoader(), new Class<?>[] {Connection.class}, new Connections(connection, alias));
    }

    /** What every statement on a connection says about its database. */
    private record Database(String system, String namespace, String alias) {
        static Database of(Connection connection, String alias) {
            String system = "sql";
            String namespace = alias;
            try {
                DatabaseMetaData meta = connection.getMetaData();
                system = meta.getDatabaseProductName().toLowerCase(Locale.ROOT).replace(" ", "");
                String catalog = connection.getCatalog();
                if (catalog != null && !catalog.isEmpty()) namespace = catalog;
            } catch (SQLException | RuntimeException ignored) {
                // A driver that can't say: the alias names it.
            }
            return new Database(system, namespace, alias);
        }

        Map<String, Object> attrs() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("db.system", system);
            attrs.put("db.vendor", system);
            attrs.put("db.client", "jdbc");
            attrs.put("db.alias", alias);
            attrs.put("db.namespace", namespace);
            return attrs;
        }
    }

    private static final class Connections implements InvocationHandler {
        private final Connection target;
        private final String alias;
        private Database database;
        private Map<String, Object> transaction;
        private Trace transactionTrace;

        Connections(Connection target, String alias) {
            this.target = target;
            this.alias = alias;
        }

        private Database database() {
            if (database == null) database = Database.of(target, alias);
            return database;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("unwrap") && args[0] == Connection.class) return target;
            if (name.equals("setAutoCommit")) transaction(!(Boolean) args[0], null);
            Object result = call(target, method, args);
            switch (name) {
                case "commit" -> transaction(false, "commit");
                case "rollback" -> transaction(false, args == null ? "rollback" : null);
                case "close" -> transaction(false, "rollback");
                default -> {
                    if (result instanceof CallableStatement statement) {
                        return statement(statement, CallableStatement.class, (String) args[0]);
                    }
                    if (result instanceof PreparedStatement statement) {
                        return statement(statement, PreparedStatement.class, (String) args[0]);
                    }
                    if (result instanceof Statement statement) return statement(statement, Statement.class, null);
                }
            }
            return result;
        }

        private Object statement(Statement statement, Class<?> type, String sql) {
            return Proxy.newProxyInstance(
                    Jdbc.class.getClassLoader(), new Class<?>[] {type}, new Statements(statement, sql, database()));
        }

        /** A transaction begins when auto-commit goes off, and ends at commit or rollback, with its outcome. */
        private void transaction(boolean begin, String outcome) {
            Trace trace = Tracepanel.current();
            if (begin) {
                if (trace == null || transaction != null) return;
                Map<String, Object> attrs = database().attrs();
                Tracepanel tracepanel = Tracepanel.get();
                attrs.put("code.origin", tracepanel == null ? null : tracepanel.stacks().origin());
                transaction = trace.begin("transaction", "transaction " + database().namespace(), attrs);
                transactionTrace = trace;
            } else if (transaction != null && outcome != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> attrs = (Map<String, Object>) transaction.get("attrs");
                attrs.put("db.transaction.outcome", outcome);
                transactionTrace.end(transaction, false);
                transaction = null;
                transactionTrace = null;
            }
        }
    }

    private static final class Statements implements InvocationHandler {
        private final Statement target;
        private final Database database;
        private String sql;
        private final List<Object> params = new ArrayList<>();
        private final List<String> batch = new ArrayList<>();

        Statements(Statement target, String sql, Database database) {
            this.target = target;
            this.sql = sql;
            this.database = database;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index
                    && method.getDeclaringClass() != Statement.class) {
                while (params.size() < index) params.add(null);
                params.set(index - 1, args[1] instanceof byte[] ? "<bytes>" : args[1]);
            } else if (name.equals("clearParameters")) {
                params.clear();
            } else if (name.equals("addBatch") && args != null && args.length == 1) {
                batch.add((String) args[0]);
            }
            boolean executes = EXECUTE.contains(name) || BATCH.contains(name);
            Trace trace = executes ? Tracepanel.current() : null;
            if (trace == null) return call(target, method, args);
            String text = args != null && args.length > 0 && args[0] instanceof String given ? given : sql;
            if (text == null && !batch.isEmpty()) text = batch.get(0);
            if (text == null) return call(target, method, args);
            long start = Trace.nowMicros();
            boolean failed = false;
            Map<String, Object> attrs = attrs(text, BATCH.contains(name));
            try {
                Object result = call(target, method, args);
                if (result instanceof ResultSet rows) return rows(rows, attrs);
                return result;
            } catch (Throwable e) {
                failed = true;
                throw e;
            } finally {
                trace.done("sql", Sql.spanName(text), attrs, start, failed);
                if (BATCH.contains(name)) batch.clear();
            }
        }

        private Map<String, Object> attrs(String text, boolean many) {
            Map<String, Object> attrs = database.attrs();
            attrs.put("db.statement", Sql.statement(text));
            attrs.put("db.fingerprint", Sql.fingerprint(text));
            List<String> values = params.stream().map(String::valueOf).toList();
            attrs.put("db.params", values);
            attrs.put("db.params_hash", params.isEmpty() ? null : Sql.hash(Json.write(values)));
            attrs.put("db.many", many);
            Tracepanel tracepanel = Tracepanel.get();
            attrs.put("code.origin", tracepanel == null ? null : tracepanel.stacks().origin());
            attrs.put("code.template", null);
            return attrs;
        }

        /** The rows a query returned, counted as the app reads them: a SELECT without a LIMIT that read too many. */
        private static ResultSet rows(ResultSet rows, Map<String, Object> attrs) {
            int[] count = {0};
            attrs.put("db.response.returned_rows", 0);
            return (ResultSet) Proxy.newProxyInstance(
                    Jdbc.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                        Object result = call(rows, method, args);
                        if (method.getName().equals("next") && Boolean.TRUE.equals(result)) {
                            attrs.put("db.response.returned_rows", ++count[0]);
                        }
                        return result;
                    });
        }
    }

    /** The real method's answer, or what it threw, not wrapped. */
    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
