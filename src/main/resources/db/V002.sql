INSERT INTO contractors (id, email, full_name) VALUES
                                                   (1, 'alex@example.test', 'Alex Morgan'),
                                                   (2, 'maya@example.test', 'Maya Chen'),
                                                   (3, 'jordan@example.test', 'Jordan Patel');

-- Fictional projects and clients for the demo.
INSERT INTO projects (id, name, client_name) VALUES
                                                 (1, 'Salesforce Experience Cloud Portal', 'Northstar Studio'),
                                                 (2, 'Salesforce Service Cloud Integration', 'Harbour Systems'),
                                                 (3, 'Salesforce Knowledge Migration', 'Atlas Partners'),
                                                 (4, 'Salesforce AI Support Agent', 'Cedar Labs');

INSERT INTO contractor_projects (contractor_id, project_id) VALUES
                                                                (1, 1), (1, 2),
                                                                (2, 2), (2, 3),
                                                                (3, 4);