CREATE TABLE timesheets (
                            id INTEGER PRIMARY KEY,
                            contractor_id INTEGER NOT NULL REFERENCES contractors(id),
                            project_id INTEGER NOT NULL REFERENCES projects(id),
                            week_start TEXT NOT NULL CHECK (strftime('%w', week_start) IS '1'),
                            status TEXT NOT NULL DEFAULT 'Draft'
                                CHECK (status IN ('Draft', 'Submitted')),
                            total_quarters INTEGER NOT NULL DEFAULT 0
                                CHECK (total_quarters BETWEEN 0 AND 672),
                            submitted_at TEXT,
                            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            UNIQUE (contractor_id, project_id, week_start)
);

CREATE TABLE timesheet_lines (
                                 id INTEGER PRIMARY KEY,
                                 timesheet_id INTEGER NOT NULL REFERENCES timesheets(id),
                                 work_date TEXT NOT NULL,
                                 quarters INTEGER NOT NULL
                                     CHECK (typeof(quarters) = 'integer' AND quarters BETWEEN 0 AND 96),
                                 note TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 500),
                                 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                 UNIQUE (timesheet_id, work_date)
);