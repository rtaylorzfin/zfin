FROM postgres:18

RUN apt-get update && \
    apt-get install -y --no-install-recommends pgbackrest && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /var/lib/pgbackrest /var/log/pgbackrest /tmp/pgbackrest && \
    chown -R postgres:postgres /var/lib/pgbackrest /var/log/pgbackrest /tmp/pgbackrest

COPY ./addUsers.sql /docker-entrypoint-initdb.d/addUsers.sql
