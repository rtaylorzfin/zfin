ARG ELK_VERSION

FROM docker.elastic.co/beats/filebeat:${ELK_VERSION}

USER root
COPY ./filebeat.yml /usr/share/filebeat/filebeat.yml
COPY ./apache.yml /usr/share/filebeat/modules.d/apache.yml
#COPY ./tomcat.yml /usr/share/filebeat/modules.d/tomcat.yml

RUN chmod go-w /usr/share/filebeat/filebeat.yml
RUN chmod go-w /usr/share/filebeat/modules.d/apache.yml
#RUN chmod go-w /usr/share/filebeat/modules.d/tomcat.yml

# Shared volume with compile: declare it so a fresh volume seeds gradle-owned, not root.
RUN mkdir -p /opt/zfin/catalina_bases/zfin.org && chown 1000:1000 /opt/zfin/catalina_bases/zfin.org

USER filebeat
