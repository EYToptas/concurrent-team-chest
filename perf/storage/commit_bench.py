#!/usr/bin/env python3
# MySQL INSERT + COMMIT latency benchmark.

import argparse
import json
import math
import os
import sys
import threading
import time

import pymysql

FILE_CAMPAIGNS = {
    "wait/io/file/innodb/innodb_log_file": "redo",
    "wait/io/file/sql/binlog": "binlog",
}
STATUS_NAMES = ("Innodb_os_log_fsyncs", "Innodb_data_fsyncs", "Innodb_os_log_written", "Innodb_data_written")
SETTINGS = ("version", "log_bin", "sync_binlog", "innodb_flush_log_at_trx_commit", "innodb_log_writer_threads",
            "innodb_use_fdatasync", "innodb_flush_method", "innodb_doublewrite", "innodb_buffer_pool_size",
            "innodb_redo_log_capacity", "innodb_io_capacity", "binlog_group_commit_sync_delay",
            "binlog_group_commit_sync_no_delay_count", "transaction_isolation", "max_connections")
INSERT = ("INSERT INTO commit_bench (worker, seq, payload, created_at) "
          "VALUES (%s, %s, %s, UTC_TIMESTAMP(6))")
PAYLOAD = "x" * 100
WALL_STEP_NOTE_MS = 100

def log(message):
    print(message, file=sys.stderr, flush=True)

def connect(args, autocommit=False):
    return pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=os.environ["BENCH_MYSQL_PASSWORD"], database=args.database,
                           autocommit=autocommit, connect_timeout=10, read_timeout=120, write_timeout=120)

def read_settings(conn):
    with conn.cursor() as cursor:
        cursor.execute("SELECT " + ", ".join("@@" + name for name in SETTINGS))
        row = cursor.fetchone()
    return {name: (value.decode() if isinstance(value, bytes) else value) for name, value in zip(SETTINGS, row)}

def require_durability(settings):
    problems = []
    if int(settings["log_bin"]) != 1:
        problems.append("log_bin=%s" % settings["log_bin"])
    if int(settings["sync_binlog"]) != 1:
        problems.append("sync_binlog=%s" % settings["sync_binlog"])
    if int(settings["innodb_flush_log_at_trx_commit"]) != 1:
        problems.append("innodb_flush_log_at_trx_commit=%s" % settings["innodb_flush_log_at_trx_commit"])
    if problems:
        raise SystemExit("Durability settings are not the defaults (%s): not measured" % ", ".join(problems))

def snapshot(conn):
    with conn.cursor() as cursor:
        cursor.execute("SELECT CAMPAIGN_NAME, COUNT_MISC, SUM_TIMER_MISC, SUM_NUMBER_OF_BYTES_WRITE "
                       "FROM performance_schema.file_summary_by_campaign_name WHERE CAMPAIGN_NAME IN (%s, %s)",
                       tuple(FILE_CAMPAIGNS))
        files = {FILE_CAMPAIGNS[name]: {"syncs": int(syncs), "syncPs": int(sync_ps), "bytesWritten": int(written)}
                 for name, syncs, sync_ps, written in cursor.fetchall()}
        cursor.execute("SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status "
                       "WHERE VARIABLE_NAME IN (%s)" % ", ".join(["%s"] * len(STATUS_NAMES)), STATUS_NAMES)
        status = {name: int(value) for name, value in cursor.fetchall()}
        cursor.execute("SELECT COUNT_POINT FROM performance_schema.campaigns_statements_summary_global_by_campaign_name "
                       "WHERE CAMPAIGN_NAME = 'statement/sql/commit'")
        status["commitStatements"] = int(cursor.fetchone()[0])
    return {"monoNs": time.perf_counter_ns(), "wallNs": time.time_ns(), "files": files, "status": status}

def summary(values_ns):
    if not values_ns:
        return None
    ordered = sorted(values_ns)

    def percentile(p):
        index = min(len(ordered) - 1, max(0, math.ceil(p / 100.0 * len(ordered)) - 1))
        return ordered[index] / 1e6

    return {"count": len(ordered), "mean": sum(ordered) / len(ordered) / 1e6, "p50": percentile(50),
            "p95": percentile(95), "p99": percentile(99), "max": ordered[-1] / 1e6}

def round_trip(conn, samples=200):
    durations = []
    with conn.cursor() as cursor:
        for _ in range(samples):
            started = time.perf_counter_ns()
            cursor.execute("SELECT 1")
            cursor.fetchall()
            durations.append(time.perf_counter_ns() - started)
    return summary(durations)

def server_delta(before, after, seconds):
    result = {}
    for log_name in ("redo", "binlog"):
        a = before["files"].get(log_name)
        b = after["files"].get(log_name)
        if a is None or b is None:
            result[log_name] = None
            continue
        syncs = b["syncs"] - a["syncs"]
        sync_ms = (b["syncPs"] - a["syncPs"]) / 1e9
        result[log_name] = {
            "syncs": syncs,
            "syncsPerSecond": syncs / seconds,
            "syncMeanMs": sync_ms / syncs if syncs > 0 else None,
            "busy": sync_ms / 1000.0 / seconds,
            "bytesPerSecond": (b["bytesWritten"] - a["bytesWritten"]) / seconds,
        }
    status = {name: after["status"].get(name, 0) - before["status"].get(name, 0)
              for name in STATUS_NAMES + ("commitStatements",)}
    result["status"] = status
    commits = status["commitStatements"]
    result["commitsPerRedoSync"] = commits / result["redo"]["syncs"] if result["redo"] and result["redo"]["syncs"] else None
    result["commitsPerBinlogSync"] = (commits / result["binlog"]["syncs"]
                                      if result["binlog"] and result["binlog"]["syncs"] else None)
    return result

def run_level(args, monitor, concurrency):
    stop = threading.Campaign()
    ready = threading.Barrier(concurrency + 1)
    samples = [[] for _ in range(concurrency)]
    errors = []

    def worker(index):
        try:
            conn = connect(args)
        except Exception as e:
            errors.append("connect: %r" % e)
            ready.abort()
            return
        seq = 0
        try:
            with conn.cursor() as cursor:
                ready.wait()
                while not stop.is_set():
                    seq += 1
                    started = time.perf_counter_ns()
                    cursor.execute(INSERT, (index, seq, PAYLOAD))
                    inserted = time.perf_counter_ns()
                    conn.commit()
                    samples[index].append((started, inserted, time.perf_counter_ns()))
        except threading.BrokenBarrierError:
            pass
        except Exception as e:
            errors.append("worker %d: %r" % (index, e))
        finally:
            conn.close()

    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(concurrency)]
    for thread in threads:
        thread.start()
    try:
        ready.wait()
    except threading.BrokenBarrierError:
        stop.set()
        return {"concurrency": concurrency, "errors": errors, "valid": False}
    time.sleep(args.warmup)
    before = snapshot(monitor)
    time.sleep(args.duration)
    after = snapshot(monitor)
    stop.set()
    for thread in threads:
        thread.join(timeout=150)

    seconds = (after["monoNs"] - before["monoNs"]) / 1e9
    commit_ns, transaction_ns = [], []
    for worker_samples in samples:
        for started, inserted, committed in worker_samples:
            if before["monoNs"] <= committed <= after["monoNs"]:
                commit_ns.append(committed - inserted)
                transaction_ns.append(committed - started)
    wall_step_ms = ((after["wallNs"] - before["wallNs"]) - (after["monoNs"] - before["monoNs"])) / 1e6
    server = server_delta(before, after, seconds)
    return {
        "concurrency": concurrency,
        "valid": not errors,
        "errors": errors,
        "windowSeconds": seconds,
        "commits": len(commit_ns),
        "commitsPerSecond": len(commit_ns) / seconds,
        "commitMs": summary(commit_ns),
        "transactionMs": summary(transaction_ns),
        "server": server,
        "wallStepMs": wall_step_ms,
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--database", default="bench")
    parser.add_argument("--levels", default="1,4,8,16,32")
    parser.add_argument("--warmup", type=float, default=5.0)
    parser.add_argument("--duration", type=float, default=30.0)
    args = parser.parse_args()
    levels = [int(part) for part in args.levels.split(",") if part.strip()]

    monitor = connect(args, autocommit=True)
    settings = read_settings(monitor)
    require_durability(settings)
    with monitor.cursor() as cursor:
        cursor.execute("DROP TABLE IF EXISTS commit_bench")
        cursor.execute("CREATE TABLE commit_bench (id BIGINT AUTO_INCREMENT PRIMARY KEY, worker INT NOT NULL, "
                       "seq BIGINT NOT NULL, payload VARCHAR(255) NOT NULL, created_at DATETIME(6) NOT NULL) "
                       "ENGINE=InnoDB")
    rtt = round_trip(monitor)
    log("    MySQL %s, sync_binlog=%s, innodb_flush_log_at_trx_commit=%s, round trip p50 %.3f ms" % (
        settings["version"], settings["sync_binlog"], settings["innodb_flush_log_at_trx_commit"], rtt["p50"]))

    results = []
    for concurrency in levels:
        log("    concurrency %d: %.0f s warm-up + %.0f s measured" % (concurrency, args.warmup, args.duration))
        level = run_level(args, monitor, concurrency)
        results.append(level)
        if level.get("commitMs"):
            log("      %.1f commits/s, commit p50 %.2f ms, p95 %.2f ms" % (
                level["commitsPerSecond"], level["commitMs"]["p50"], level["commitMs"]["p95"]))
        for error in level["errors"]:
            log("      error: " + error)
        time.sleep(2)

    with monitor.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM commit_bench")
        rows = cursor.fetchone()[0]
    monitor.close()
    json.dump({"format": 1, "settings": settings, "roundTripMs": rtt, "rowsInserted": rows, "levels": results,
               "warmupSeconds": args.warmup, "durationSeconds": args.duration}, sys.stdout, indent=1)
    sys.stdout.write("\n")

if __name__ == "__main__":
    main()
