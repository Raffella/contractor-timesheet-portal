package com.raffella.timesheet;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProjectController {

    private static final String DATABASE_URL = "jdbc:sqlite:data/portal.sqlite";

    public record Project(long id, String name, String client) {}

    @GetMapping("/api/projects")
    public List<Project> getAssignedProjects(HttpServletRequest request) throws SQLException {
        var session = request.getSession(false);

        if (session == null
                || !(session.getAttribute("contractorEmail") instanceof String email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        }

        String sql = """
                SELECT p.id, p.name, p.client_name
                FROM projects p
                JOIN contractor_projects cp ON cp.project_id = p.id
                JOIN contractors c ON c.id = cp.contractor_id
                WHERE c.email = ?
                ORDER BY p.name
                """;

        List<Project> projects = new ArrayList<>();

        try (var connection = DriverManager.getConnection(DATABASE_URL);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);

            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    projects.add(new Project(
                            rows.getLong("id"),
                            rows.getString("name"),
                            rows.getString("client_name")
                    ));
                }
            }
        }

        return projects;
    }
}