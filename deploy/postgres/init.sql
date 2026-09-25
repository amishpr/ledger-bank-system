-- Runs once, the first time the Postgres volume is created. The ledger
-- database comes from POSTGRES_DB; these are the other two services'.
CREATE DATABASE recurring OWNER ledger;
CREATE DATABASE insights OWNER ledger;
