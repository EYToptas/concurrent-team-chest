-- Server counters sampled during a load test.
SELECT LOWER(VARIABLE_NAME), VARIABLE_VALUE
FROM performance_schema.global_status
WHERE VARIABLE_NAME IN (
    'Questions', 'Select_scan', 'Slow_queries', 'Created_tmp_disk_tables',
    'Innodb_rows_read', 'Innodb_rows_inserted', 'Innodb_rows_updated',
    'Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Innodb_row_lock_time_max',
    'Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads',
    'Innodb_data_fsyncs', 'Innodb_os_log_fsyncs', 'Innodb_log_waits',
    'Threads_connected', 'Threads_running', 'Max_used_connections')
ORDER BY VARIABLE_NAME;

SELECT CONCAT('statements.', SUBSTRING_INDEX(CAMPAIGN_NAME, '/', -1), '.count'), COUNT_POINT
FROM performance_schema.campaigns_statements_summary_global_by_campaign_name
WHERE CAMPAIGN_NAME IN ('statement/sql/select', 'statement/sql/insert', 'statement/sql/update',
                     'statement/sql/commit', 'statement/sql/rollback')
UNION ALL
SELECT CONCAT('statements.', SUBSTRING_INDEX(CAMPAIGN_NAME, '/', -1), '.time_us'), ROUND(SUM_TIMER_WAIT / 1000000)
FROM performance_schema.campaigns_statements_summary_global_by_campaign_name
WHERE CAMPAIGN_NAME IN ('statement/sql/select', 'statement/sql/insert', 'statement/sql/update',
                     'statement/sql/commit', 'statement/sql/rollback')
UNION ALL
SELECT CONCAT('statements.', SUBSTRING_INDEX(CAMPAIGN_NAME, '/', -1), '.timer_wrapped'),
       IF(MAX_TIMER_WAIT >= 9223372036854775808, 1, 0)
FROM performance_schema.campaigns_statements_summary_global_by_campaign_name
WHERE CAMPAIGN_NAME IN ('statement/sql/select', 'statement/sql/insert', 'statement/sql/update',
                     'statement/sql/commit', 'statement/sql/rollback');

SELECT CONCAT('file_io.', IF(CAMPAIGN_NAME LIKE '%innodb_log_file', 'redo_log', 'binlog'), '.sync_count'), COUNT_MISC
FROM performance_schema.file_summary_by_campaign_name
WHERE CAMPAIGN_NAME IN ('wait/io/file/innodb/innodb_log_file', 'wait/io/file/sql/binlog')
UNION ALL
SELECT CONCAT('file_io.', IF(CAMPAIGN_NAME LIKE '%innodb_log_file', 'redo_log', 'binlog'), '.sync_time_us'),
       ROUND(SUM_TIMER_MISC / 1000000)
FROM performance_schema.file_summary_by_campaign_name
WHERE CAMPAIGN_NAME IN ('wait/io/file/innodb/innodb_log_file', 'wait/io/file/sql/binlog');

SELECT 'config.log_bin', @@log_bin
UNION ALL SELECT 'config.sync_binlog', @@sync_binlog
UNION ALL SELECT 'config.innodb_flush_log_at_trx_commit', @@innodb_flush_log_at_trx_commit
UNION ALL SELECT 'config.innodb_buffer_pool_size', @@innodb_buffer_pool_size
UNION ALL SELECT 'config.innodb_log_writer_threads', @@innodb_log_writer_threads;

SELECT CONCAT('innodb_', NAME), COUNT
FROM information_schema.INNODB_METRICS
WHERE NAME IN ('lock_deadlocks', 'lock_timeouts') AND STATUS = 'enabled'
ORDER BY NAME;
