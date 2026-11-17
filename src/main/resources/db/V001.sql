CREATE TABLE contractors (
                             id INTEGER PRIMARY KEY,
                             email TEXT NOT NULL UNIQUE,
                             full_name TEXT NOT NULL
);

CREATE TABLE projects (
                          id INTEGER PRIMARY KEY,
                          name TEXT NOT NULL,
                          client_name TEXT NOT NULL
);

CREATE TABLE contractor_projects (
                                     contractor_id INTEGER NOT NULL REFERENCES contractors(id),
                                     project_id INTEGER NOT NULL REFERENCES projects(id),
                                     PRIMARY KEY (contractor_id, project_id)
);