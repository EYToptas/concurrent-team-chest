# MySQL commit benchmark client.
FROM python:3.12-slim
RUN pip install --no-cache-dir PyMySQL==1.1.1
COPY commit_bench.py /bench/commit_bench.py
ENTRYPOINT ["python", "-u", "/bench/commit_bench.py"]
