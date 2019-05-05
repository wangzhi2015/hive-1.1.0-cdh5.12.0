-- Upgrade MetaStore schema from 1.1.0 to 1.1.0-cdh5.12.0

SOURCE 041-HIVE-16556.mysql.sql;

ALTER TABLE VERSION ADD COLUMN SCHEMA_VERSION_V2 VARCHAR(255);
UPDATE VERSION SET SCHEMA_VERSION='1.1.0', VERSION_COMMENT='Hive release version 1.1.0-cdh5.12.0', SCHEMA_VERSION_V2='1.1.0-cdh5.12.0' where VER_ID=1;
SELECT 'Finished upgrading MetaStore schema from 1.1.0 to 1.1.0-cdh5.12.0' AS ' ';
