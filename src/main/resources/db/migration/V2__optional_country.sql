-- The sign-up form asks for a name, a picture and two languages, not a country, so a profile may have none.
ALTER TABLE users ALTER COLUMN country_code DROP NOT NULL;
