#!/bin/sh
set -eu
umask 077
exec python -m uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8080 --workers 1 --no-server-header
