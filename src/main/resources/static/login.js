const app = document.querySelector("#app");
let email = "";

const icons = {
    mark: '<path d="M4 6h16M4 12h10M4 18h7m5-2 2 2 3-4"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 1 1 8 0v3m-4 5v2"/>',
    arrow: '<path d="M4 12h15m-6-6 6 6-6 6"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18m-13 5h2m4 0h2"/>'
};

function icon(name) {
    return `<svg class="icon" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="1.7"
                 stroke-linecap="round" stroke-linejoin="round"
                 aria-hidden="true">${icons[name]}</svg>`;
}

function showSignIn(step = "email", notice = "") {
    const verifying = step === "code";

    app.innerHTML = `
        <div class="auth">
            <div class="auth-main">
                <div class="brand">
                    <span class="brand-mark">${icon("mark")}</span>Contractor Portal
                </div>
                <main id="main" class="auth-center">
                    <form class="auth-form" id="auth-form">
                        <span class="brand-mark">${icon(verifying ? "lock" : "mail")}</span>
                        <h1>${verifying ? "Enter your code" : "Welcome back"}</h1>
                        ${verifying ? `
                            <p>Enter the six-digit sign-in code requested for
                               <strong id="requested-email"></strong>. It expires in 10 minutes.</p>
                            <label for="code">Sign-in code</label>
                            <input id="code" type="text" inputmode="numeric"
                                   autocomplete="one-time-code" pattern="[0-9]{6}"
                                   maxlength="6" placeholder="000000" required>
                        ` : `
                            <p>Sign in to the contractor portal with a one-time email code.</p>
                            <label for="email">Work email</label>
                            <input id="email" type="email" autocomplete="email"
                                   maxlength="254" placeholder="you@company.com" required>
                        `}
                        <button class="button primary" type="submit">
                            ${verifying ? "Verify and sign in" : "Send sign-in code"}
                            ${icon("arrow")}
                        </button>
                        <p id="message" class="form-message" role="status" hidden></p>
                        ${verifying ? `
                            <div class="auth-tools">
                                <button type="button" class="button ghost" id="change-email">
                                    Use a different email
                                </button>
                                <button type="button" class="button ghost" id="resend">
                                    Resend code
                                </button>
                            </div>
                        ` : ""}
                        <div class="dev">
                            Local demo. Read the code in the IntelliJ Run console.
                            ${verifying ? "" : 'Try <button type="button" id="demo-email">alex@example.test</button>.'}
                        </div>
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
                <div>
                    <h2>Log your project hours</h2>
                    <p>Save a draft and submit your week when it’s ready.</p>
                </div>
            </aside>
        </div>`;

    const form = document.querySelector("#auth-form");
    const input = document.querySelector(verifying ? "#code" : "#email");
    const message = document.querySelector("#message");

    function showMessage(text, isError = false) {
        message.textContent = text;
        message.classList.toggle("error", isError);
        message.hidden = false;
    }

    async function runRequest(action) {
        const buttons = form.querySelectorAll("button");
        buttons.forEach(button => { button.disabled = true; });

        try {
            await action();
        } catch (error) {
            showMessage(error.message, true);
        } finally {
            buttons.forEach(button => { button.disabled = false; });
        }
    }

    if (notice) showMessage(notice);

    if (verifying) {
        document.querySelector("#requested-email").textContent = email;

        document.querySelector("#change-email").onclick = () => {
            showSignIn();
            document.querySelector("#email").focus();
        };

        document.querySelector("#resend").onclick = () => runRequest(async () => {
            const result = await post("/api/auth/request", { email });
            showMessage(result.message);
            input.value = "";
            input.focus();
        });
    } else {
        input.value = email;
        document.querySelector("#demo-email").onclick = () => {
            input.value = "alex@example.test";
            input.focus();
        };
    }

    form.onsubmit = event => {
        event.preventDefault();

        runRequest(async () => {
            if (verifying) {
                await post("/api/auth/verify", { email, code: input.value });
                await loadSession();
                return;
            }

            email = input.value.trim().toLowerCase();
            const result = await post("/api/auth/request", { email });
            showSignIn("code", result.message);
            document.querySelector("#code").focus();
        });
    };
}

async function post(url, body) {
    const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
    });

    const data = await response.json().catch(() => ({}));

    if (!response.ok) {
        throw new Error(data.message || "Something went wrong. Try again.");
    }

    return data;
}

async function loadSession() {
    try {
        const response = await fetch("/api/auth/session", { cache: "no-store" });

        if (response.status === 401) {
            showSignIn();
            return;
        }

        if (!response.ok) {
            throw new Error("Could not check your session.");
        }

        const contractor = await response.json();
        showWorkspace(contractor);
    } catch {
        showSignIn("email", "Could not load your session. Please try again.");
    }
}

function showWorkspace(contractor) {
    const signOutButton = '<button type="button" class="button ghost" data-sign-out>Sign out</button>';

    app.innerHTML = `
        <div class="shell">
            <aside class="sidebar">
                <div class="brand">
                    <span class="brand-mark">${icon("mark")}</span>Contractor Portal
                </div>
                <nav aria-label="Workspace">
                    <div class="eyebrow">Contractor workspace</div>
                    <div class="nav-item" aria-current="page">
                        ${icon("calendar")} Timesheets
                    </div>
                </nav>
                <footer>
                    <div class="profile">
                        <span class="avatar" aria-hidden="true">AM</span>
                        <div class="profile-copy">
                            <strong id="contractor-name"></strong>
                            <small id="contractor-email"></small>
                        </div>
                    </div>
                    ${signOutButton}
                </footer>
            </aside>
            <div>
                <header class="topbar">
                    <div class="crumb">${icon("calendar")} <strong>Timesheets</strong></div>
                    <div class="mobile-user">${signOutButton}</div>
                </header>
                <main id="main" class="content" tabindex="-1">
                    <div class="page-head">
                        <div>
                            <div class="eyebrow">Contractor workspace</div>
                            <h1>My timesheets</h1>
                            <p>Review your drafts and submitted project hours.</p>
                        </div>
                    </div>
                    <section class="card" aria-labelledby="history">
                        <div class="card-head"><h2 id="history">Timesheet history</h2></div>
                        <div class="empty">
                            <strong>No timesheets yet</strong>
                        </div>
                    </section>
                    <p id="workspace-message" role="status"></p>
                </main>
            </div>
        </div>`;

    document.querySelector("#contractor-name").textContent = contractor.name;
    document.querySelector("#contractor-email").textContent = contractor.email;
    document.querySelector("#main").focus();

    document.querySelectorAll("[data-sign-out]").forEach(button => {
        button.onclick = async () => {
            button.disabled = true;

            try {
                const result = await post("/api/auth/logout", {});
                email = "";
                showSignIn("email", result.message);
            } catch {
                document.querySelector("#workspace-message").textContent =
                    "Could not sign out. Please try again.";
                button.disabled = false;
            }
        };
    });
}

loadSession();