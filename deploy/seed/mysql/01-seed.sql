-- Seed for trying a MySQL source by hand: the same table the PostgreSQL seed
-- has, so one policy can be read against both.
--
-- One table carrying a national ID, an email, a salary and a branch code is
-- enough to exercise row filtering, column masking, cell masking and column
-- hiding at once. Every value is made up.

CREATE DATABASE IF NOT EXISTS sales;

CREATE TABLE IF NOT EXISTS sales.customer (
    id          INT PRIMARY KEY,
    full_name   VARCHAR(120) NOT NULL,
    email       VARCHAR(160) NOT NULL,
    citizen_id  VARCHAR(20)  NOT NULL,
    phone       VARCHAR(20),
    salary      DECIMAL(12, 2),
    branch_code VARCHAR(20)  NOT NULL,
    country     CHAR(2)      NOT NULL DEFAULT 'TH',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT IGNORE INTO sales.customer
    (id, full_name, email, citizen_id, phone, salary, branch_code, country) VALUES
    (1, 'Customer One',   'one@example.test',   '1100000000011', '0810000001', 45000.00, 'BKK-01', 'TH'),
    (2, 'Customer Two',   'two@example.test',   '1100000000022', '0810000002', 62000.00, 'BKK-01', 'TH'),
    (3, 'Customer Three', 'three@example.test', '1100000000033', '0810000003', 38000.00, 'CNX-01', 'TH'),
    (4, 'Customer Four',  'four@example.test',  'S0000000A',     '+6500000004', 88000.00, 'SIN-01', 'SG');
