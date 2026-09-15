-- Seed for the manual end-to-end walkthrough in the plan, section 6.
--
-- Deliberately small and deliberately realistic: one table carrying a national
-- ID, an email, a salary and a branch code is enough to exercise row filtering,
-- column masking, cell masking and column hiding all at once.

CREATE SCHEMA IF NOT EXISTS sales;

CREATE TABLE IF NOT EXISTS sales.customer (
    id          integer PRIMARY KEY,
    full_name   text NOT NULL,
    email       text NOT NULL,
    citizen_id  text NOT NULL,
    phone       text,
    salary      numeric(12,2),
    branch_code text NOT NULL,
    country     text NOT NULL DEFAULT 'TH',
    created_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO sales.customer (id, full_name, email, citizen_id, phone, salary, branch_code, country) VALUES
    (1, 'Somchai Wong',    'somchai@example.co.th', '1103700123456', '0812345678', 45000.00,  'BKK-01', 'TH'),
    (2, 'Nattaporn Sri',   'nattaporn@example.co.th','1209800234567', '0823456789', 62000.00,  'BKK-01', 'TH'),
    (3, 'Chaiwat Phan',    'chaiwat@example.co.th',  '3101900345678', '0834567890', 38000.00,  'CNX-02', 'TH'),
    (4, 'Lim Wei Ling',    'weiling@example.com.sg', 'S1234567D',     '+6591234567', 88000.00, 'SIN-01', 'SG')
ON CONFLICT (id) DO NOTHING;
