-- InnoDB settings for one load test.
SET @redo_log_capacity_mib = IFNULL(@redo_log_capacity_mib, 0);
SET @io_capacity = IFNULL(@io_capacity, 0);

SET GLOBAL innodb_redo_log_capacity = CAST(
    IF(@redo_log_capacity_mib > 0, @redo_log_capacity_mib * 1048576, @@GLOBAL.innodb_redo_log_capacity) AS UNSIGNED);
SET GLOBAL innodb_io_capacity = CAST(IF(@io_capacity > 0, @io_capacity, @@GLOBAL.innodb_io_capacity) AS UNSIGNED);

SELECT 'innodb_redo_log_capacity', @@GLOBAL.innodb_redo_log_capacity
UNION ALL SELECT 'innodb_io_capacity', @@GLOBAL.innodb_io_capacity;
