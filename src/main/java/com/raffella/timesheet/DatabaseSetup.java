package com.raffella.timesheet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSetup implements CommandLineRunner {

    private static final String DATABASE_URL = "jdbc:sqlite:data/portal.sqlite";

    @Override
    public void run(String... args) throws IOException, SQLException {
        Files.createDirectories(Path.of("data"));

        try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version TEXT PRIMARY KEY NOT NULL
                    )
                    """);
            }

            applyMigration(connection, "V001.sql");
            applyMigration(connection, "V002.sql");
            applyMigration(connection, "V003.sql");
            applyMigration(connection, "V004.sql");
        }
    }

    private void applyMigration(Connection connection, String filename)
            throws IOException, SQLException {
        try (var check = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            check.setString(1, filename);
            try (var result = check.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }

        String script = new ClassPathResource("db/" + filename)
                .getContentAsString(StandardCharsets.UTF_8);

        connection.setAutoCommit(false);
        try (var statement = connection.createStatement();
             var record = connection.prepareStatement(
                     "INSERT INTO schema_migrations (version) VALUES (?)")) {

            // These two scripts contain simple, semicolon-separated statements.
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql.strip());
                }
            }

            record.setString(1, filename);
            record.executeUpdate();
            connection.commit();
            System.out.println("Applied migration " + filename);
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}