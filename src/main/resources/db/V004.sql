CREATE TABLE contractor_login_codes (
                                        contractor_id INTEGER PRIMARY KEY
                                            REFERENCES contractors(id) ON DELETE CASCADE,
                                        code_hash BLOB NOT NULL,
                                        expires_at INTEGER NOT NULL,
                                        failed_attempts INTEGER NOT NULL DEFAULT 0
                                            CHECK (failed_attempts BETWEEN 0 AND 5)
);

CREATE TABLE contractor_otp_requests (
                                         id INTEGER PRIMARY KEY,
                                         contractor_id INTEGER NOT NULL
                                             REFERENCES contractors(id) ON DELETE CASCADE,
                                         requested_at INTEGER NOT NULL
);

CREATE INDEX idx_contractor_otp_requests
    ON contractor_otp_requests (contractor_id, requested_at);