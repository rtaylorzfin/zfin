FROM lscr.io/linuxserver/fail2ban:latest

RUN apk add bind-tools

COPY jail.local /defaults/fail2ban/
COPY apache-unavailable.conf /defaults/fail2ban/filter.d/
COPY apache-forbidden.conf /defaults/fail2ban/filter.d/
COPY zfin-search.conf /defaults/fail2ban/filter.d/
COPY zfin-botsearch.conf /defaults/fail2ban/filter.d/
COPY ignore_ip_check.sh /usr/local/bin/

# Shared build-cache volumes with compile: declare them so fresh volumes seed gradle-owned, not root.
RUN mkdir -p /home/gradle/.gradle /home/gradle/.m2 /home/gradle/.npm \
    && chown -R 1000:1000 /home/gradle
