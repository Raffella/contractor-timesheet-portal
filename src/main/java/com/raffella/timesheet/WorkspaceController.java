package com.raffella.timesheet;

import static com.raffella.timesheet.Database.number;
import static com.raffella.timesheet.Database.one;

import java.sql.SQLException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final Database database;
    private final Timesheets timesheets;

    WorkspaceController(Database database, Timesheets timesheets) {
        this.database = database;
        this.timesheets = timesheets;
    }

    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("local", true);
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspace(HttpServletRequest request) throws SQLException {
        var contractor = contractor(request);
        long id = number(contractor, "id");
        return Map.of("user", contractor, "today", timesheets.today().toString(),
                "projects", timesheets.projects(id), "timesheets", timesheets.list(id));
    }

    @GetMapping("/timesheet")
    public Map<String, Object> get(
            @RequestParam("projectId") long projectId, @RequestParam("week") String week,
            HttpServletRequest request) throws SQLException {
        return timesheets.get(number(contractor(request), "id"), projectId, week);
    }

    @PutMapping("/timesheet")
    public Map<String, Object> save(@RequestBody Timesheets.Entry entry,
                                    HttpServletRequest request) throws SQLException {
        return timesheets.save(number(contractor(request), "id"), entry);
    }

    private Map<String, Object> contractor(HttpServletRequest request) throws SQLException {
        var session = request.getSession(false);
        if (session == null
                || !(session.getAttribute("contractorEmail") instanceof String email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        }

        var contractor = database.read(connection -> one(connection,
                "SELECT id, email, full_name AS name FROM contractors WHERE email = ?", email));
        if (contractor == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        }
        return contractor;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("message", "Check the values and try again."));
    }
}
