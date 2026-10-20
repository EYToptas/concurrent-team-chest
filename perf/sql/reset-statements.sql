-- Resets statement statistics before the load.
TRUNCATE TABLE performance_schema.campaigns_statements_summary_by_digest;
SELECT 'statement_statistics', 'reset';
