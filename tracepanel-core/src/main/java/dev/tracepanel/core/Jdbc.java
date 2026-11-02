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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQL through JDBC, whatever runs it (JPA, Hibernate, Spring Data, jOOQ, JdbcTemplate): each statement a span of the
 * current trace with its text, fingerprint, parameters, rows and the app's line that ran it, and each transaction
 * one around its statements.
 */
public final class Jdbc {
    private static final Set<String> EXECUTE = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");
    private static final Set<String> BATCH = Set.of("executeBatch", "executeLargeBatch");
    private static final String RETURNED_ROWS = "db.response.returned_rows";
    /** The app's databases by alias, unrecorded: what EXPLAIN runs on (Explain), outside every trace. */
    private static final Map<String, javax.sql.DataSource> DATABASES = new ConcurrentHashMap<>();

    private Jdbc() {}

    /**
     * A DataSource whose connections are recorded: for an app with no framework integration to find it (Ktor with
     * Exposed or plain JDBC), Jdbc.dataSource(hikari, "default").
     */
    public static javax.sql.DataSource dataSource(javax.sql.DataSource dataSource, String alias) {
        if (Proxy.isProxyClass(dataSource.getClass()) && Proxy.getInvocationHandler(dataSource) instanceof DataSources) return dataSource;
        register(dataSource, alias);
        return (javax.sql.DataSource) Proxy.newProxyInstance(Jdbc.class.getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class}, new DataSources(dataSource, alias));
    }

    /** A database the app's queries run on, by the alias they are recorded with: the console can EXPLAIN them. */
    public static void register(javax.sql.DataSource dataSource, String alias) {
        DATABASES.put(alias, dataSource);
    }

    static boolean hasDatabases() {
        return !DATABASES.isEmpty();
    }

    static javax.sql.DataSource database(String alias) {
        return DATABASES.get(alias);
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
        // The rows of the query that ran last, which the app reads after its span ended.
        private Rows rows;

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
            } else if (rows != null && name.equals("getResultSet")) {
                // The results of the statement execute() ran: their rows count into its span.
                Object result = call(target, method, args);
                return result instanceof ResultSet set ? rows.wrap(set) : result;
            } else if (rows != null && name.equals("close")) {
                rows.flush();
            }
            boolean executes = EXECUTE.contains(name) || BATCH.contains(name);
            if (executes && rows != null) {
                // Executing again closes the last results: what was read of them is all they gave.
                rows.flush();
                rows = null;
            }
            Trace trace = executes ? Tracepanel.current() : null;
            if (trace == null) return call(target, method, args);
            String text = args != null && args.length > 0 && args[0] instanceof String given ? given : sql;
            if (text == null && !batch.isEmpty()) text = batch.get(0);
            if (text == null) return call(target, method, args);
            long start = Trace.nowMicros();
            Map<String, Object> attrs = attrs(text, BATCH.contains(name));
            Object result;
            try {
                result = call(target, method, args);
            } catch (Throwable e) {
                trace.done("sql", Sql.spanName(text), attrs, start, true);
                throw e;
            } finally {
                if (BATCH.contains(name)) batch.clear();
            }
            // A query's span lasts as long as it ran; the rows it gave are those the app reads from it, after.
            boolean results = result instanceof ResultSet || Boolean.TRUE.equals(result) && name.equals("execute");
            if (results) attrs.put(RETURNED_ROWS, 0);
            Map<String, Object> span = trace.done("sql", Sql.spanName(text), attrs, start, false);
            if (result instanceof ResultSet set) {
                rows = new Rows(trace, span, true);
                return rows.wrap(set);
            }
            if (name.equals("execute")) rows = new Rows(trace, span, results);
            return result;
        }

        private Map<String, Object> attrs(String text, boolean many) {
            Map<String, Object> attrs = database.attrs();
            attrs.put("db.statement", Sql.statement(text));
            attrs.put("db.fingerprint", Sql.fingerprint(text));
            // Numbers and booleans as they are, everything else as its text: EXPLAIN binds them back by type.
            List<Object> values = params.stream().map(Jdbc::scalar).toList();
            // A value bound to a secret's column (password = ?) is masked; the hash, which tells an N+1 from
            // duplicates, is of the values themselves.
            attrs.put("db.params", Secrets.params(text, values));
            attrs.put("db.params_hash", params.isEmpty() ? null : Sql.hash(Json.write(values)));
            attrs.put("db.many", many);
            Tracepanel tracepanel = Tracepanel.get();
            // The ORM that made the query, when the agent knows its N+1 fix (join fetch, .with()): Hibernate, Exposed.
            if (tracepanel != null) attrs.put("db.client", tracepanel.stacks().client());
            String origin = tracepanel == null ? null : tracepanel.stacks().origin();
            attrs.put("code.origin", origin);
            // A query only library code made (Jackson writing a lazy relation): which library code asked for it.
            if (origin == null && tracepanel != null) attrs.put("code.caller", tracepanel.stacks().caller());
            attrs.put("code.template", null);
            return attrs;
        }
    }

    /**
     * The rows a query returned, counted as the app reads them (a SELECT without a LIMIT that read too many), into its
     * span: when its results are read to their end or closed, its statement runs again or closes, and when the trace
     * ends, as they stand then. Rows read after the trace ended aren't in it.
     */
    private static final class Rows {
        private final Trace trace;
        private final Map<String, Object> span;
        private final AtomicInteger count = new AtomicInteger();
        // Whether the query gave results: an update execute() ran gives none, unless the app asks for them.
        private volatile boolean results;

        Rows(Trace trace, Map<String, Object> span, boolean results) {
            this.trace = trace;
            this.span = span;
            this.results = results;
            trace.late(this::flush);
        }

        void flush() {
            if (results) trace.spanAttr(span, RETURNED_ROWS, count.get());
        }

        ResultSet wrap(ResultSet set) {
            results = true;
            return (ResultSet) Proxy.newProxyInstance(
                    Jdbc.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                        Object result = call(set, method, args);
                        switch (method.getName()) {
                            case "next" -> {
                                if (Boolean.TRUE.equals(result)) count.incrementAndGet();
                                else flush();
                            }
                            case "close" -> flush();
                            default -> {}
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

    static Object scalar(Object value) {
        return value == null || value instanceof Number || value instanceof Boolean ? value : String.valueOf(value);
    }
}
