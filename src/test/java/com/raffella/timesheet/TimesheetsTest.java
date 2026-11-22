package com.raffella.timesheet;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class TimesheetsTest {

    @Autowired
    private Timesheets timesheets;
    @Autowired
    private WorkspaceController workspaceController;
    @Autowired
    private ProjectController projectController;
    @Autowired
    private TimesheetController timesheetController;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) throws Exception {
        Path folder = Files.createTempDirectory("timesheets-test");
        properties.add("portal.database-url",
                () -> "jdbc:sqlite:" + folder.resolve("portal.sqlite"));
    }

    @Test
    void reopensDraftWithSavedHoursAndTotal() throws Exception {
        String monday = "2026-11-16";
        var entries = List.of(
                new Timesheets.Line(monday, new BigDecimal("7.25"), "Portal work"),
                new Timesheets.Line("2026-11-17", new BigDecimal("5"), "Testing")
        );

        timesheets.save(1L, new Timesheets.Entry(1L, monday, entries, false, null));

        var reopened = timesheets.get(1L, 1L, monday);

        assertEquals("Draft", reopened.get("status"));
        assertEquals(12.25, ((Number) reopened.get("hours")).doubleValue(), 0.001);

        var savedLines = (List<?>) reopened.get("lines");
        assertEquals(2, savedLines.size());

        var firstDay = (Map<?, ?>) savedLines.get(0);
        var secondDay = (Map<?, ?>) savedLines.get(1);
        assertEquals(7.25, ((Number) firstDay.get("hours")).doubleValue(), 0.001);
        assertEquals(5.0, ((Number) secondDay.get("hours")).doubleValue(), 0.001);
    }
    @Test
    void rejectsEditsAfterSubmission() throws Exception {
        String monday = "2026-11-09";
        var entries = List.of(
                new Timesheets.Line(monday, new BigDecimal("7.25"), "Portal work")
        );

        var submitted = timesheets.save(1L,
                new Timesheets.Entry(1L, monday, entries, true, null));

        assertEquals("Submitted", submitted.get("status"));

        var changedEntries = List.of(
                new Timesheets.Line(monday, new BigDecimal("8"), "Edited hours")
        );

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> timesheets.save(1L,
                        new Timesheets.Entry(1L, monday, changedEntries, false,
                                (String) submitted.get("updatedAt")))
        );

        assertEquals(409, error.getStatusCode().value());

        var reopened = timesheets.get(1L, 1L, monday);
        assertEquals(7.25, ((Number) reopened.get("hours")).doubleValue(), 0.001);
    }
    @Test
    void rejectsSavingForAnUnassignedProject() {
        String monday = "2026-11-09";
        var entries = List.of(
                new Timesheets.Line(monday, new BigDecimal("2"), "Project work")
        );
        var draft = new Timesheets.Entry(4L, monday, entries, false, null);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> timesheets.save(1L, draft)
        );

        assertEquals(404, error.getStatusCode().value());
    }
    @Test
    void rejectsHoursOutsideQuarterHourIncrements() {
        String monday = "2026-11-02";
        var entries = List.of(
                new Timesheets.Line(monday, new BigDecimal("7.10"), "Project work")
        );
        var draft = new Timesheets.Entry(1L, monday, entries, false, null);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> timesheets.save(1L, draft)
        );

        assertEquals(400, error.getStatusCode().value());
    }
    @Test
    void rejectsSavingAnOlderCopyOfADraft() throws Exception {
        String monday = "2026-10-26";

        var originalEntries = List.of(
                new Timesheets.Line(monday, new BigDecimal("2"), "Original")
        );
        var original = timesheets.save(1L,
                new Timesheets.Entry(1L, monday, originalEntries, false, null));
        String oldRevision = (String) original.get("updatedAt");

        var newerEntries = List.of(
                new Timesheets.Line(monday, new BigDecimal("3"), "Newer save")
        );
        timesheets.save(1L,
                new Timesheets.Entry(1L, monday, newerEntries, false, oldRevision));

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> timesheets.save(1L,
                        new Timesheets.Entry(1L, monday, originalEntries, false, oldRevision))
        );

        assertEquals(409, error.getStatusCode().value());

        var reopened = timesheets.get(1L, 1L, monday);
        assertEquals(3.0, ((Number) reopened.get("hours")).doubleValue(), 0.001);
    }
    @Test
    void rejectsTimesheetsForFutureWeeks() {
        String nextMonday = timesheets.today()
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
                .toString();

        var draft = new Timesheets.Entry(1L, nextMonday, List.of(), false, null);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> timesheets.save(1L, draft)
        );

        assertEquals(400, error.getStatusCode().value());
    }
    @Test
    void rejectsWorkspaceAccessWithoutSigningIn() {
        var request = new MockHttpServletRequest();

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> workspaceController.workspace(request)
        );

        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void showsOnlyAlexsAssignedProjects() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "alex@example.test");

        var workspace = workspaceController.workspace(request);
        var projects = (List<?>) workspace.get("projects");
        var projectIds = projects.stream()
                .map(project -> (Map<?, ?>) project)
                .map(project -> ((Number) project.get("id")).longValue())
                .sorted()
                .toList();

        assertEquals(List.of(1L, 2L), projectIds);
    }
    @Test
    void savesAndReopensDraftThroughWorkspaceController() throws Exception {
        String monday = "2026-10-19";
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "alex@example.test");

        var entries = List.of(
                new Timesheets.Line(monday, new BigDecimal("3.25"), "Portal work")
        );
        var draft = new Timesheets.Entry(1L, monday, entries, false, null);

        var saved = workspaceController.save(draft, request);
        assertEquals("Draft", saved.get("status"));

        var reopened = workspaceController.get(1L, monday, request);
        assertEquals(saved.get("id"), reopened.get("id"));
        assertEquals(3.25, ((Number) reopened.get("hours")).doubleValue(), 0.001);

        var lines = (List<?>) reopened.get("lines");
        assertEquals(1, lines.size());
        assertEquals("Portal work", ((Map<?, ?>) lines.get(0)).get("note"));
    }
    @Test
    void listsOnlyMayasAssignedProjects() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "maya@example.test");

        var projectIds = projectController.getAssignedProjects(request).stream()
                .map(ProjectController.Project::id)
                .sorted()
                .toList();

        assertEquals(List.of(2L, 3L), projectIds);
    }
    @Test
    void savesDraftThroughEarlierEndpoint() throws Exception {
        String monday = "2026-10-12";
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "alex@example.test");

        var entries = List.of(
                new TimesheetController.DailyEntry(
                        monday, new BigDecimal("4.50"), "Project work")
        );

        var saved = timesheetController.saveDraft(
                new TimesheetController.DraftRequest(1L, monday, entries), request);

        assertEquals("Draft", saved.status());
        assertEquals(4.5, saved.totalHours().doubleValue(), 0.001);

        var reopened = timesheets.get(1L, 1L, monday);
        assertEquals(saved.id(), ((Number) reopened.get("id")).longValue());
        assertEquals(4.5, ((Number) reopened.get("hours")).doubleValue(), 0.001);
    }
    @Test
    void earlierDraftEndpointRequiresSignIn() {
        var request = new MockHttpServletRequest();
        var draft = new TimesheetController.DraftRequest(
                1L, "2026-10-12", List.of());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> timesheetController.saveDraft(draft, request)
        );

        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void earlierDraftEndpointRejectsNonMondayWeek() {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "alex@example.test");
        var draft = new TimesheetController.DraftRequest(
                1L, "2026-10-13", List.of());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> timesheetController.saveDraft(draft, request)
        );

        assertEquals(400, error.getStatusCode().value());
    }
    @Test
    void earlierDraftEndpointRejectsInvalidEntries() {
        String monday = "2026-10-12";
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "alex@example.test");

        var invalidDrafts = List.of(
                new TimesheetController.DraftRequest(null, monday, List.of()),
                new TimesheetController.DraftRequest(1L, "2026-02-30", List.of()),
                new TimesheetController.DraftRequest(1L, monday, List.of(
                        new TimesheetController.DailyEntry(
                                "2026-10-19", new BigDecimal("1"), ""))),
                new TimesheetController.DraftRequest(1L, monday, List.of(
                        new TimesheetController.DailyEntry(
                                monday, new BigDecimal("1.10"), ""))),
                new TimesheetController.DraftRequest(1L, monday, List.of(
                        new TimesheetController.DailyEntry(
                                monday, new BigDecimal("1"), "a".repeat(501))))
        );

        for (var draft : invalidDrafts) {
            var error = org.junit.jupiter.api.Assertions.assertThrows(
                    ResponseStatusException.class,
                    () -> timesheetController.saveDraft(draft, request)
            );
            assertEquals(400, error.getStatusCode().value());
        }
    }
    @Test
    void earlierDraftEndpointRejectsUnassignedProject() {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "alex@example.test");

        var draft = new TimesheetController.DraftRequest(
                4L, "2026-10-12", List.of());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> timesheetController.saveDraft(draft, request)
        );

        assertEquals(409, error.getStatusCode().value());
    }
}