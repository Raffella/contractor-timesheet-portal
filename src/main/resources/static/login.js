"use strict";

const icons = {
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18m-13 5h2m4 0h2"/>',
  mark: '<path d="M4 6h16M4 12h10M4 18h7m5-2 2 2 3-4"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 1 1 8 0v3m-4 5v2"/>',
  arrow: '<path d="M4 12h15m-6-6 6 6-6 6"/>',
  back: '<path d="M20 12H5m6-6-6 6 6 6"/>',
  left: '<path d="m15 6-6 6 6 6"/>',
  right: '<path d="m9 6 6 6-6 6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  logout: '<path d="M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5m-1-9h12m-5-5 5 5-5 5"/>',
  file: '<path d="M13 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10Zm0 0v7h7M8 14h8m-8 3h5"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  save: '<path d="M5 3h12l4 4v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Zm2 0v6h10V3M7 21v-7h10v7"/>',
  check: '<path d="m5 12 4 4L19 6"/>'
};
const icon = name => `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name]}</svg>`;
const brand = () => `<div class="brand"><span class="brand-mark">${icon("mark")}</span>Contractor Portal</div>`;
const $ = selector => document.querySelector(selector);
const escape = value => String(value ?? "").replace(/[&<>"']/g, char => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
}[char]));

const app = $("#app");
let workspace = null;
let localMode = false;
let email = "";
let filterProject = "";
let filterStatus = "All";
let current = null;
let dirty = false;
let busy = false;
let toastTimer;
let viewRequest = 0;

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function api(path, method = "GET", body) {
  let response;
  try {
    response = await fetch("/api" + path, {
      method, headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
  } catch {
    throw new ApiError(0, "Could not reach the portal. Check the connection and try again.");
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError(response.status, data.message || "Something went wrong. Try again.");
  return data;
}

function announce(text) {
  const toast = $("#toast");
  clearTimeout(toastTimer);
  toast.textContent = text;
  toast.hidden = false;
  toastTimer = setTimeout(() => { toast.hidden = true; }, 4200);
}

function message(text, error = false) {
  const target = $("#message");
  target.textContent = text;
  target.hidden = false;
  target.classList.toggle("error", error);
}

function authView(step = "email", notice = "") {
  viewRequest++;
  workspace = null;
  current = null;
  dirty = false;
  busy = false;
  const verify = step === "code";
  app.innerHTML = `
    <div class="auth">
      <div class="auth-main">
        ${brand()}
        <main id="main" class="auth-center">
          <form class="auth-form" id="auth-form">
            <span class="brand-mark">${icon(verify ? "lock" : "mail")}</span>
            <h1>${verify ? "Enter your code" : "Welcome back"}</h1>
            <p>${verify ? `Enter the six-digit sign-in code requested for <strong>${escape(email)}</strong>. It expires in 10 minutes.` : "Sign in to the contractor portal with a one-time email code."}</p>
            ${verify ? `<label for="code">Sign-in code</label><input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="000000" required>` :
              `<label for="email">Work email</label><input id="email" type="email" autocomplete="email" maxlength="254" placeholder="you@company.com" value="${escape(email)}" required>`}
            <button class="button primary" id="auth-submit">${verify ? "Verify and sign in" : "Send sign-in code"} ${icon("arrow")}</button>
            <p id="message" class="form-message" role="status" hidden></p>
            ${verify ? `<div class="auth-tools"><button type="button" class="button ghost" id="change-email">Use a different email</button><button type="button" class="button ghost" id="resend">Resend code</button></div>` : ""}
            ${localMode ? `<div class="dev">Local demo. Read the code in the IntelliJ Run console.${verify ? "" : ' Try <button type="button" id="demo-email">alex@example.test</button>.'}</div>` : ""}
          </form>
        </main>
        <footer class="auth-foot">Contractor Timesheet Portal</footer>
      </div>
      <aside class="auth-art" aria-label="Contractor timesheets">
        <div class="illustration" aria-hidden="true">
          <header><span class="brand-mark">${icon("calendar")}</span> Weekly timesheet</header>
          <div class="mini-row"><span>Mon</span><span class="mini-track"></span><span>7 h</span></div>
          <div class="mini-row"><span>Tue</span><span class="mini-track"></span><span>5 h</span></div>
          <div class="mini-row"><span>Wed</span><span class="mini-track"></span><span>8 h</span></div>
          <footer><span class="muted">Weekly total</span><strong>20 hours</strong></footer>
        </div>
        <div><h2>Log your project hours</h2><p>Save a draft and submit your week when it’s ready.</p></div>
      </aside>
    </div>`;
  if (notice) message(notice);
  $("#demo-email")?.addEventListener("click", () => {
    $("#email").value = "alex@example.test";
    $("#email").focus();
  });
  $("#change-email")?.addEventListener("click", () => { authView(); $("#email").focus(); });
  $("#resend")?.addEventListener("click", event => perform(event.currentTarget, async () => {
    const result = await api("/auth/request", "POST", { email });
    message(result.message);
    $("#code").value = "";
    $("#code").focus();
  }));
  $("#auth-form").addEventListener("submit", event => {
    event.preventDefault();
    perform($("#auth-submit"), async () => {
      if (verify) {
        await api("/auth/verify", "POST", { email, code: $("#code").value });
        workspace = await api("/workspace");
        filterProject = "";
        filterStatus = "All";
        listView(true);
      } else {
        email = $("#email").value.trim().toLowerCase();
        const result = await api("/auth/request", "POST", { email });
        authView("code", result.message);
        $("#code").focus();
      }
    });
  });
}

async function perform(button, operation) {
  button.disabled = true;
  try { await operation(); }
  catch (error) { message(error.message, true); }
  finally { button.disabled = false; }
}

function iso(date) {
  return date.toISOString().slice(0, 10);
}

function shift(isoDate, count) {
  const date = new Date(isoDate + "T00:00:00Z");
  date.setUTCDate(date.getUTCDate() + count);
  return iso(date);
}

function monday(isoDate) {
  const day = new Date(isoDate + "T00:00:00Z").getUTCDay();
  return shift(isoDate, -((day + 6) % 7));
}

const dateLabel = (date, options) => new Intl.DateTimeFormat("en-GB", {
  timeZone: "UTC", ...options
}).format(new Date(date + "T00:00:00Z"));
const shortDate = date => dateLabel(date, { day: "numeric", month: "short" });
const weekLabel = week => `${shortDate(week)} – ${dateLabel(shift(week, 6), { day: "numeric", month: "short", year: "numeric" })}`;
const hoursLabel = hours => Number(hours).toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const tag = status => `<span class="tag ${status.toLowerCase()}">${status}</span>`;

function shell(body, section = "") {
  const { user } = workspace;
  const initials = user.name.trim().split(/\s+/).slice(0, 2).map(word => Array.from(word)[0]).join("").toUpperCase();
  const avatar = `<span class="avatar" aria-hidden="true">${escape(initials)}</span>`;
  app.innerHTML = `
    <div class="shell">
      <aside class="sidebar">
        ${brand()}
        <nav aria-label="Workspace"><div class="eyebrow">Contractor workspace</div>
          <button class="nav-item" data-list aria-current="page">${icon("calendar")} Timesheets</button>
        </nav>
        <footer>
          <div class="profile">${avatar}<div class="profile-copy"><strong>${escape(user.name)}</strong><small>${escape(user.email)}</small></div></div>
          <button class="nav-item" data-logout>${icon("logout")} Sign out</button>
        </footer>
      </aside>
      <div>
        <header class="topbar">
          <div class="crumb">${icon("calendar")} <strong>Timesheets</strong>${section ? `${icon("right")}<span>${section}</span>` : ""}</div>
          <div class="mobile-user">${avatar}<button class="button ghost icon-button" aria-label="Sign out" data-logout>${icon("logout")}</button></div>
        </header>
        <main id="main" class="content" tabindex="-1">${body}</main>
      </div>
    </div>`;
  app.querySelectorAll("[data-list]").forEach(button => button.addEventListener("click", async () => {
    if (!busy && await allowLeave()) listView(true);
  }));
  app.querySelectorAll("[data-logout]").forEach(button => button.addEventListener("click", async () => {
    if (busy || !await allowLeave()) return;
    try {
      await api("/auth/logout", "POST", {});
      email = "";
      authView("email", "You’ve been signed out.");
      $("#email").focus();
    } catch (error) { announce(error.message); }
  }));
}

function listView(focus = false) {
  viewRequest++;
  current = null;
  dirty = false;
  const active = workspace.projects.some(project => project.active);
  const week = monday(workspace.today);
  shell(`
    <div class="page-head">
      <div><div class="eyebrow">Contractor workspace</div><h1>My timesheets</h1><p>Review your drafts and submitted project hours.</p></div>
      <button class="button primary" id="new-sheet" ${active ? "" : "disabled"}>${icon("plus")} New timesheet</button>
    </div>
    <section class="banner" aria-label="Current week">
      <div class="banner-main"><span class="tile-icon">${icon("calendar")}</span><div><h2>This week</h2><p>${weekLabel(week)} · ${active ? "Choose a project to log your time." : "No active projects are assigned to you."}</p></div></div>
      <button class="button secondary" id="this-week" ${active ? "" : "disabled"}>Open this week ${icon("arrow")}</button>
    </section>
    <section class="card" aria-labelledby="history">
      <div class="card-head"><div><h2 id="history">Timesheet history</h2><p id="result-count" aria-live="polite"></p></div></div>
      <div class="toolbar">
        <div class="tabs" aria-label="Filter by status">
          ${["All", "Draft", "Submitted"].map(status => `<button data-status="${status}" aria-pressed="${status === filterStatus}">${status === "Draft" ? "Drafts" : status}</button>`).join("")}
        </div>
        <select id="filter-project" class="filter" aria-label="Filter by project">
          <option value="">All projects</option>
          ${[...new Map([...workspace.projects.map(project => [project.id, project.name]), ...workspace.timesheets.map(sheet => [sheet.projectId, sheet.project])]).entries()]
            .map(([id, name]) => `<option value="${id}" ${String(id) === filterProject ? "selected" : ""}>${escape(name)}</option>`).join("")}
        </select>
      </div>
      <div id="results"></div>
    </section>`);
  $("#new-sheet").onclick = () => start(week);
  $("#this-week").onclick = () => start(week);
  $("#filter-project").onchange = event => {
    filterProject = event.target.value;
    renderList();
  };
  app.querySelectorAll("[data-status]").forEach(button => button.onclick = () => {
    filterStatus = button.dataset.status;
    app.querySelectorAll("[data-status]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
    renderList();
  });
  renderList();
  if (focus) $("#main").focus();
}

function renderList() {
  const results = workspace.timesheets.filter(sheet =>
    (filterStatus === "All" || sheet.status === filterStatus)
    && (!filterProject || String(sheet.projectId) === filterProject));
  $("#result-count").textContent = `${results.length} ${results.length === 1 ? "timesheet" : "timesheets"}`;
  const target = $("#results");
  if (!results.length) {
    const hasHistory = workspace.timesheets.length > 0;
    target.innerHTML = `<div class="empty"><span class="tile-icon">${icon("file")}</span>
      <strong>${hasHistory ? "No matching timesheets" : "No timesheets yet"}</strong>
      <p>${hasHistory ? "Clear the filters to see all timesheets." : workspace.projects.some(project => project.active) ? "Create a timesheet to start tracking hours for an assigned project." : "Your timesheets will appear here once you have an assigned project."}</p>
      ${hasHistory ? '<button class="button secondary" id="empty-action">Clear filters</button>' :
        workspace.projects.some(project => project.active) ? '<button class="button secondary" id="empty-action">Create a timesheet</button>' : ""}
    </div>`;
    $("#empty-action")?.addEventListener("click", () => {
      if (!hasHistory) return start(monday(workspace.today));
      filterStatus = "All";
      filterProject = "";
      listView(true);
    });
    return;
  }
  target.innerHTML = `<div class="table-wrap" tabindex="0" role="region" aria-label="Timesheet history table"><table class="sheet-table">
    <thead><tr><th scope="col">Project</th><th scope="col">Week</th><th scope="col">Hours</th><th scope="col">Status</th><th scope="col"><span class="sr-only">Action</span></th></tr></thead>
    <tbody>${results.map(sheet => `<tr>
      <td><div class="table-title">${escape(sheet.project)}</div><div class="table-sub">${escape(sheet.client)}</div></td>
      <td>${weekLabel(sheet.week)}</td><td>${hoursLabel(sheet.hours)}</td><td>${tag(sheet.status)}</td>
      <td><button class="button ghost" data-open="${sheet.id}" aria-label="${sheet.status === "Draft" ? "Edit" : "View"} ${escape(sheet.project)}, ${weekLabel(sheet.week)}">${sheet.status === "Draft" ? "Edit" : "View"} ${icon("right")}</button></td>
    </tr>`).join("")}</tbody></table></div>`;
  target.querySelectorAll("[data-open]").forEach(button => button.onclick = () => {
    const sheet = results.find(item => String(item.id) === button.dataset.open);
    openSheet(sheet.projectId, sheet.week);
  });
}

function start(week) {
  const projects = workspace.projects.filter(project => project.active);
  if (!projects.length) return;
  const project = projects.find(item => String(item.id) === filterProject) ?? projects[0];
  openSheet(project.id, week);
}

async function openSheet(projectId, week, focus = true) {
  const request = ++viewRequest;
  try {
    const query = new URLSearchParams({ projectId, week });
    const result = await api("/timesheet?" + query);
    if (request !== viewRequest) return;
    current = result;
    dirty = false;
    editor(focus);
  } catch (error) {
    if (request !== viewRequest) return;
    if (error.status === 401) authView("email", "Your session has expired. Sign in again.");
    else announce(error.message);
  }
}

function row(day, index, lines, locked) {
  const name = dateLabel(day, { weekday: "long" });
  const date = shortDate(day);
  const heading = `<div class="table-title">${name}</div><div class="table-sub">${date}</div>`;
  if (day > workspace.today) {
    return `<tr class="future"><td>${heading}</td><td colspan="2">Available on ${date}</td></tr>`;
  }
  const line = lines.get(day);
  if (locked) {
    return `<tr><td>${heading}</td><td>${hoursLabel(line?.hours ?? 0)}</td><td class="saved-note">${escape(line?.note || "—")}</td></tr>`;
  }
  return `<tr data-day="${day}"><td>${heading}</td>
    <td><input type="number" id="hours-${index}" aria-label="${name} hours" min="0" max="24" step="0.25" inputmode="decimal" placeholder="0.00" value="${escape(line?.hours)}"></td>
    <td><textarea id="note-${index}" aria-label="${name} note" maxlength="500" rows="1" placeholder="Add a note">${escape(line?.note)}</textarea></td></tr>`;
}

function editor(focus) {
  const { week, projectId, status, hours } = current;
  const locked = status === "Submitted";
  const dates = Array.from({ length: 7 }, (_, i) => shift(week, i));
  const lines = new Map(current.lines.map(line => [line.date, line]));
  const projects = workspace.projects.filter(project => project.active || project.id === projectId);
  if (!projects.some(project => project.id === projectId)) {
    projects.push({ id: projectId, name: current.project, active: false });
  }
  const active = projects.some(project => project.id === projectId && project.active);
  const editable = !locked && active;
  shell(`
    <button class="button ghost back" data-list>${icon("back")} My timesheets</button>
    <div class="page-head"><div><div class="eyebrow">${weekLabel(week)}</div>
      <h1>${locked ? "Submitted timesheet" : status === "New" ? "New timesheet" : "Edit timesheet"}</h1>
      <p>${locked ? "Review the hours submitted for this week." : editable ? "Record daily hours and optional notes for your project." : "This draft is read-only because the project is unavailable."}</p>
    </div></div>
    <form id="entry" class="editor-grid">
      <div class="editor-stack">
        <section class="card selection" aria-label="Project and week">
          <div><label for="project">Project</label><select id="project" ${editable ? "" : "disabled"}>${projects.map(project =>
            `<option value="${project.id}" ${project.id === projectId ? "selected" : ""}>${escape(project.name)}</option>`).join("")}</select></div>
          <div><label for="week">Week starting Monday</label><div class="week-control">
            <button id="previous" class="button secondary icon-button" type="button" aria-label="Previous week" ${editable ? "" : "disabled"}>${icon("left")}</button>
            <input id="week" type="date" value="${week}" max="${workspace.today}" ${editable ? "" : "readonly"} required>
            <button id="next" class="button secondary icon-button" type="button" aria-label="Next week" ${editable && shift(week, 7) <= workspace.today ? "" : "disabled"}>${icon("right")}</button>
          </div></div>
        </section>
        <section class="card" aria-labelledby="daily">
          <div class="card-head"><div><h2 id="daily">Daily entries</h2><p>${editable ? "Use 0.25 for 15 minutes. Notes are optional." : "Hours and notes for the selected week."}</p></div></div>
          <table class="entries">
            <thead><tr><th scope="col">Day</th><th scope="col">Hours</th><th scope="col">Note</th></tr></thead>
            <tbody>${dates.map((day, index) => row(day, index, lines, !editable)).join("")}</tbody>
          </table>
        </section>
      </div>
      <aside class="card summary" aria-labelledby="summary-heading">
        <h2 id="summary-heading">Weekly summary</h2>
        ${tag(status)}
        <div class="total"><output id="total">${hoursLabel(hours ?? 0)}</output> <small>hours</small></div>
        <p id="days-count"></p>
        <hr>
        ${editable ? `<p>Save your progress or submit when the week is ready. Submission locks this entry.</p>
          <button type="button" class="button secondary" id="save">${icon("save")} Save draft</button>
          <button type="submit" class="button primary" id="submit">${icon("check")} Submit timesheet</button>` :
          `<p>${locked ? "This timesheet is locked for editing." : "Project access is required to update this draft."}</p>`}
        <p id="message" class="form-message" role="status" hidden></p>
      </aside>
    </form>`, locked ? "Submitted timesheet" : "Weekly entry");

  const summary = () => {
    const values = editable ? [...app.querySelectorAll("[data-day] input")].map(input => Number(input.value))
      : current.lines.map(line => line.hours);
    $("#total").textContent = hoursLabel(values.reduce((total, value) => total + value, 0));
    const days = values.filter(value => value > 0).length;
    $("#days-count").textContent = `${days} of 7 days with hours`;
  };
  summary();
  if (editable) {
    $("#entry").addEventListener("input", event => {
      if (event.target.closest("[data-day]")) { dirty = true; summary(); }
    });
    $("#project").onchange = event => changeSelection(Number(event.target.value), week);
    $("#week").onchange = event => {
      const value = event.target.value;
      if (!value || value > workspace.today) {
        event.target.value = week;
        message("Choose a date on or before today.", true);
        return;
      }
      const start = monday(value);
      if (start !== value) announce("Selected the Monday of that week.");
      if (start === week) event.target.value = week;
      else changeSelection(projectId, start);
    };
    $("#previous").onclick = () => changeSelection(projectId, shift(week, -7));
    $("#next").onclick = () => changeSelection(projectId, shift(week, 7));
    $("#save").onclick = () => save(false);
    $("#entry").onsubmit = event => { event.preventDefault(); save(true); };
  } else {
    $("#entry").onsubmit = event => event.preventDefault();
  }
  if (focus) $("#main").focus();
}

async function changeSelection(projectId, week) {
  if (busy || !await allowLeave()) {
    $("#project").value = String(current.projectId);
    $("#week").value = current.week;
    return;
  }
  $("#project").value = String(current.projectId);
  $("#week").value = current.week;
  openSheet(projectId, week);
}

function confirmAction(title, copy, label, symbol) {
  const dialog = $("#dialog");
  if (dialog.open) return Promise.resolve(false);
  $("#dialog-title").textContent = title;
  $("#dialog-copy").textContent = copy;
  $("#confirm").textContent = label;
  $("#dialog-icon").innerHTML = icon(symbol);
  dialog.returnValue = "";
  dialog.showModal();
  return new Promise(resolve => dialog.addEventListener("close", () => resolve(dialog.returnValue === "confirm"), { once: true }));
}

function allowLeave() {
  return dirty ? confirmAction("Discard unsaved changes?", "Any edits since your last save will be lost.", "Discard changes", "file") : Promise.resolve(true);
}

async function save(submit) {
  if (busy || !$("#entry").reportValidity()) return;
  if (submit && !await confirmAction("Submit this timesheet?", "Your weekly hours and notes will be locked after submission.", "Submit timesheet", "lock")) return;
  busy = true;
  viewRequest++;
  const lines = [...app.querySelectorAll("[data-day]")].map(row => ({
    date: row.dataset.day, hours: Number(row.querySelector("input").value || 0), note: row.querySelector("textarea").value
  }));
  const controls = [...$("#entry").querySelectorAll("input,select,textarea,button:not(:disabled)")];
  controls.forEach(control => { control.disabled = true; });
  try {
    const result = await api("/timesheet", "PUT", {
      projectId: current.projectId, week: current.week, lines, submit, revision: current.updatedAt ?? null
    });
    const { lines: dailyLines, ...saved } = result;
    workspace.timesheets = [saved, ...workspace.timesheets.filter(sheet => sheet.id !== saved.id)]
      .sort((a, b) => b.week.localeCompare(a.week) || b.id - a.id);
    current = { ...saved, lines: dailyLines };
    dirty = false;
    editor(false);
    const target = submit ? $("#main") : $("#save");
    target.focus();
    announce(submit ? "Timesheet submitted." : "Draft saved.");
  } catch (error) {
    if (error.status === 401) {
      message("Your session expired. Keep this tab open to copy your entries before signing in again.", true);
    } else {
      message(error.message, true);
    }
  } finally {
    controls.forEach(control => { control.disabled = false; });
    busy = false;
  }
}

window.addEventListener("beforeunload", event => {
  if (dirty) event.preventDefault();
});

async function boot() {
  try {
    const [config, session] = await Promise.all([
      api("/config"), api("/workspace").catch(error => {
        if (error.status === 401) return null;
        throw error;
      })
    ]);
    localMode = config.local;
    workspace = session;
    if (workspace) listView();
    else authView();
  } catch (error) {
    $("#main").innerHTML = '<div><p id="startup-error"></p><button class="button secondary" id="retry">Try again</button></div>';
    $("#startup-error").textContent = error.message;
    $("#retry").onclick = boot;
  }
}

boot();
