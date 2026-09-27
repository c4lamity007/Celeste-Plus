FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv/auth

RUN groupadd --system --gid 10001 auth \
    && useradd --system --uid 10001 --gid auth --no-create-home auth

COPY requirements.txt ./
RUN python -m pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY releases ./releases
COPY entrypoint.sh /usr/local/bin/celeste-auth-entrypoint

RUN chmod 0755 /usr/local/bin/celeste-auth-entrypoint \
    && mkdir -p /data/releases \
    && chown -R auth:auth /data

USER auth
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/celeste-auth-entrypoint"]
