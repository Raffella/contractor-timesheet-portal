package com.raffella.timesheet;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TimesheetController {

    private static final String DATABASE_URL = "jdbc:sqlite:data/portal.sqlite";
    private static final ZoneId PORTAL_ZONE = ZoneId.of("Asia/Kolkata");

    public record DraftRequest(Long projectId, String week, List<DailyEntry> lines) {}

    public record DailyEntry(String date, BigDecimal hours, String note) {}

    private record ValidLine(LocalDate date, int quarters, String note) {}

    public record SavedDraft(long id, String status, BigDecimal totalHours) {}

    @PutMapping("/api/timesheets/draft")
    public SavedDraft saveDraft(
            @RequestBody DraftRequest draft,
            HttpServletRequest request) throws SQLException {

        var session = request.getSession(false);
        if (session == null
                || !(session.getAttribute("contractorEmail") instanceof String email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        }

        if (draft.projectId() == null || draft.projectId() <= 0) {
            throw badRequest("Choose a project.");
        }

        LocalDate today = LocalDate.now(PORTAL_ZONE);
        LocalDate week = parseDate(draft.week());
        if (week.getDayOfWeek() != DayOfWeek.MONDAY || week.isAfter(today)) {
            throw badRequest("Choose a Monday on or before today.");
        }

        List<ValidLine> lines = validateLines(draft.lines(), week, today);
        int totalQuarters = lines.stream().mapToInt(ValidLine::quarters).sum();

        try (var connection = DriverManager.getConnection(DATABASE_URL)) {
            try (var settings = connection.createStatement()) {
                settings.execute("PRAGMA foreign_keys = ON");
                settings.execute("PRAGMA busy_timeout = 5000");
            }

            connection.setAutoCommit(false);

            try {
                long timesheetId;

                // Create a draft only for an assigned project.
                // If that contractor, project and week already have a draft, update it.
                try (var save = connection.prepareStatement("""
                        INSERT INTO timesheets
                            (contractor_id, project_id, week_start, total_quarters)
                        SELECT c.id, cp.project_id, ?, ?
                        FROM contractors c
                        JOIN contractor_projects cp ON cp.contractor_id = c.id
                        WHERE c.email = ? AND cp.project_id = ?
                        ON CONFLICT (contractor_id, project_id, week_start)
                        DO UPDATE SET
                            total_quarters = excluded.total_quarters,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE timesheets.status = 'Draft'
                        RETURNING id
                        """)) {
                    save.setString(1, week.toString());
                    save.setInt(2, totalQuarters);
                    save.setString(3, email);
                    save.setLong(4, draft.projectId());

                    try (var result = save.executeQuery()) {
                        if (!result.next()) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "This project is unavailable or the timesheet is already submitted.");
                        }
                        timesheetId = result.getLong("id");
                    }
                }

                // Each save sends the full set of daily entries.
                try (var delete = connection.prepareStatement(
                        "DELETE FROM timesheet_lines WHERE timesheet_id = ?")) {
                    delete.setLong(1, timesheetId);
                    delete.executeUpdate();
                }

                try (var insert = connection.prepareStatement("""
                        INSERT INTO timesheet_lines (timesheet_id, work_date, quarters, note)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    for (ValidLine line : lines) {
                        insert.setLong(1, timesheetId);
                        insert.setString(2, line.date().toString());
                        insert.setInt(3, line.quarters());
                        insert.setString(4, line.note());
                        insert.addBatch();
                    }

                    if (!lines.isEmpty()) {
                        insert.executeBatch();
                    }
                }

                connection.commit();
                BigDecimal totalHours = BigDecimal.valueOf(totalQuarters)
                        .divide(BigDecimal.valueOf(4));
                return new SavedDraft(timesheetId, "Draft", totalHours);
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    private static List<ValidLine> validateLines(
            List<DailyEntry> entries, LocalDate week, LocalDate today) {

        if (entries == null || entries.size() > 7) {
            throw badRequest("Enter up to seven daily rows.");
        }

        var dates = new HashSet<LocalDate>();
        var validLines = new ArrayList<ValidLine>();

        for (DailyEntry entry : entries) {
            if (entry == null || entry.hours() == null) {
                throw badRequest("Each daily entry needs a date and hours.");
            }

            LocalDate day = parseDate(entry.date());
            if (day.isBefore(week) || day.isAfter(week.plusDays(6))
                    || day.isAfter(today) || !dates.add(day)) {
                throw badRequest("Use each date once, within the selected week and through today.");
            }

            BigDecimal hours = entry.hours();
            if (hours.signum() < 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0
                    || hours.remainder(new BigDecimal("0.25")).signum() != 0) {
                throw badRequest("Hours must be between 0 and 24 in steps of 0.25.");
            }

            String note = entry.note() == null ? "" : entry.note();
            if (note.indexOf(0) >= 0 || note.codePointCount(0, note.length()) > 500) {
                throw badRequest("Use up to 500 characters of normal text for each note.");
            }

            int quarters = hours.multiply(BigDecimal.valueOf(4)).intValueExact();
            validLines.add(new ValidLine(day, quarters, note));
        }

        return validLines;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw badRequest("Use a valid date in YYYY-MM-DD format.");
        }

        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw badRequest("Use a valid date in YYYY-MM-DD format.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}