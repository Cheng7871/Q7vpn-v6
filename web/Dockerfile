FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    Q7VPN_DB_PATH=/data/q7vpn.sqlite3 \
    PORT=10000

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN mkdir -p /data /app/instance

EXPOSE 10000

HEALTHCHECK --interval=30s --timeout=4s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:10000/health',timeout=3).status==200 else 1)"

# 2 个 worker：SQLite 已开 WAL + busy_timeout，够用且省内存
CMD ["gunicorn", "-w", "2", "-b", "0.0.0.0:10000", "--access-logfile", "-", "--error-logfile", "-", "app:app"]
