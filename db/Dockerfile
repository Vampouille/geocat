FROM debian:wheezy

RUN apt-get update \
 && apt-get install -y --no-install-recommends --no-install-suggests postgresql-9.1-postgis sudo \
 && rm -rf /var/lib/apt/lists/*

ENV PGDATA=/etc/postgresql/9.1/main

ADD docker-entrypoint.sh /
ADD wait-for-it/wait-for-it.sh /
RUN mkdir /docker-entrypoint.d/
ADD docker-entrypoint.d/* /docker-entrypoint.d/

CMD sudo -u postgres /usr/lib/postgresql/9.1/bin/postgres
ENTRYPOINT /docker-entrypoint.sh
 
