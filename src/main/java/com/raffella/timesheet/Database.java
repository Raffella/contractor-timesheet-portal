package com.raffella.timesheet;

import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
final class Database {
    private static final String DATABASE_URL = "jdbc:sqlite:data/portal.sqlite";

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(DATABASE_URL);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA recursive_triggers=OFF");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(Connection connection) throws SQLException;
    }

    <T> T read(Operation<T> operation) throws SQLException {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            T result = operation.run(connection);
            connection.commit();
            return result;
        }
    }

    <T> T write(Operation<T> operation) throws SQLException {
        try (var connection = connect(); var transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                T result = operation.run(connection);
                transaction.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException error) {
                try { transaction.execute("ROLLBACK"); }
                catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            }
        }
    }

    private static PreparedStatement statement(Connection connection, String sql, Object... args) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }

    static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = statement(connection, sql, args)) {
            return statement.executeUpdate();
        }
    }

    static List<Map<String, Object>> rows(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = statement(connection, sql, args); var result = statement.executeQuery()) {
            var rows = new ArrayList<Map<String, Object>>();
            var columns = result.getMetaData();
            while (result.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    row.put(columns.getColumnLabel(i), result.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    static Map<String, Object> one(Connection connection, String sql, Object... args) throws SQLException {
        return rows(connection, sql, args).stream().findFirst().orElse(null);
    }

    static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

}
