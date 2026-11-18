package com.raffella.timesheet;

import static com.raffella.timesheet.Database.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
final class Timesheets {
    private final Database db;
    private final ZoneId zone;
    private static final String SHEET = """
        SELECT t.id,t.project_id AS projectId,p.name AS project,p.client_name AS client,
               t.week_start AS week,t.status,t.total_quarters / 4.0 AS hours,
               t.updated_at AS updatedAt,t.submitted_at AS submittedAt
        FROM timesheets t JOIN projects p ON p.id=t.project_id
        """;

    record Line(String date, BigDecimal hours, String note) {}
    record Entry(Long projectId, String week, List<Line> lines, Boolean submit, String revision) {}
    private record ValidLine(LocalDate date, int quarters, String note) {}

    Timesheets(Database db, Environment config) {
        this.db = db;
        zone = ZoneId.of(config.getProperty("PORTAL_ZONE", "Asia/Kolkata"));
    }

    LocalDate today() {
        return LocalDate.now(zone);
    }

    List<Map<String, Object>> projects(long user) throws SQLException {
        return db.read(c -> rows(c, """
            SELECT p.id,p.name,p.client_name AS client,1 AS active FROM projects p JOIN contractor_projects a ON a.project_id=p.id
            WHERE a.contractor_id=? ORDER BY p.name
            """, user));
    }

    List<Map<String, Object>> list(long user) throws SQLException {
        return db.read(c -> rows(c, SHEET + """
            WHERE t.contractor_id=? ORDER BY t.week_start DESC,t.id DESC
            """, user));
    }

    Map<String, Object> get(long user, long project, String rawWeek) throws SQLException {
        var week = week(rawWeek);
        return db.read(c -> {
            var sheet = find(c, user, project, week);
            if (sheet != null) return withLines(c, sheet);
            requireProject(c, user, project);
            return Map.of("status", "New", "projectId", project, "week", week.toString(), "lines", List.of());
        });
    }

    Map<String, Object> save(long user, Entry input) throws SQLException {
        if (input.projectId() == null || input.projectId() <= 0) throw bad("Select an assigned project.");
        var week = week(input.week());
        var lines = validate(input.lines(), week);
        long project = input.projectId();
        return db.write(c -> {
            var sheet = find(c, user, project, week);
            if (sheet != null && sheet.get("status").equals("Submitted")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This timesheet has already been submitted.");
            }
            requireProject(c, user, project);
            if (sheet != null && !Objects.equals(input.revision(), sheet.get("updatedAt"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This timesheet changed. Reopen it before saving again.");
            }
            if (sheet == null) {
                update(c, "INSERT INTO timesheets(contractor_id,project_id,week_start) VALUES(?,?,?)",
                        user, project, week.toString());
                sheet = find(c, user, project, week);
            }
            long id = number(sheet, "id");
            for (var line : lines) {
                update(c, """
                    INSERT INTO timesheet_lines(timesheet_id,work_date,quarters,note) VALUES(?,?,?,?)
                    ON CONFLICT(timesheet_id,work_date) DO UPDATE SET quarters=excluded.quarters,note=excluded.note
                    """, id, line.date().toString(), line.quarters(), line.note());
            }
            boolean submitted = Boolean.TRUE.equals(input.submit());
            String revision = Instant.now().toString();
            update(c, """
                UPDATE timesheets SET status=?,submitted_at=?,updated_at=?,
                total_quarters=(SELECT COALESCE(SUM(quarters),0) FROM timesheet_lines WHERE timesheet_id=?)
                WHERE id=?
                """, submitted ? "Submitted" : "Draft", submitted ? revision : null, revision, id, id);
            return withLines(c, find(c, user, project, week));
        });
    }

    private List<ValidLine> validate(List<Line> lines, LocalDate week) {
        if (lines == null || lines.size() > 7) throw bad("Enter up to seven daily lines.");
        Set<LocalDate> seen = new HashSet<>();
        var result = new ArrayList<ValidLine>();
        var today = today();
        for (Line line : lines) {
            if (line == null) throw bad("Each daily line must contain a date and hours.");
            LocalDate day = date(line.date());
            if (day.isBefore(week) || day.isAfter(week.plusDays(6)) || day.isAfter(today) || !seen.add(day)) {
                throw bad("Use each date once, within the selected week and through today.");
            }
            BigDecimal hours = line.hours();
            String note = line.note() == null ? "" : line.note();
            if (hours == null || hours.signum() < 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0
                    || hours.multiply(BigDecimal.valueOf(4)).stripTrailingZeros().scale() > 0) {
                throw bad("Hours must be between 0 and 24 in increments of 0.25.");
            }
            if (note.indexOf(0) >= 0 || note.codePointCount(0, note.length()) > 500) {
                throw bad("Each note can have up to 500 characters.");
            }
            result.add(new ValidLine(day, hours.multiply(BigDecimal.valueOf(4)).intValueExact(), note));
        }
        return result;
    }

    private LocalDate week(String value) {
        var date = date(value);
        if (date.getDayOfWeek() != DayOfWeek.MONDAY || date.isAfter(today())) {
            throw bad("Choose a Monday on or before today.");
        }
        return date;
    }

    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw bad("Enter a valid date.");
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException error) { throw bad("Enter a valid date."); }
    }

    private static Map<String, Object> find(Connection c, long user, long project, LocalDate week) throws SQLException {
        return one(c, SHEET + "WHERE t.contractor_id=? AND t.project_id=? AND t.week_start=?",
                user, project, week.toString());
    }

    private static void requireProject(Connection c, long user, long project) throws SQLException {
        if (one(c, """
            SELECT p.id FROM projects p JOIN contractor_projects a ON a.project_id=p.id
            WHERE a.contractor_id=? AND p.id=?
            """, user, project) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This project is unavailable.");
        }
    }

    private static Map<String, Object> withLines(Connection c, Map<String, Object> sheet) throws SQLException {
        sheet.put("lines", rows(c, """
            SELECT work_date AS date,quarters/4.0 AS hours,note FROM timesheet_lines
            WHERE timesheet_id=? ORDER BY work_date
            """, sheet.get("id")));
        return sheet;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
