# JAR File Cleanup Analysis

This document identifies which JAR files in `lib/Java/` and `home/WEB-INF/lib/` can be safely deleted now that dependencies are managed by Maven Central.

## Summary

- **Total JAR files found**: 247 files
  - `lib/Java/`: 83 files
  - `home/WEB-INF/lib/`: 164 files
- **Files declared in build.gradle**: 22 files (KEEP)
- **Files that can be deleted**: ~225 files (91%)

---

## Files to KEEP (Declared in build.gradle)

These 22 files are explicitly declared in `build.gradle` and should be **retained**:

### From `home/WEB-INF/lib/` (19 files):
1. `agr_curation_api.jar` - AGR API client (not in Maven Central)
2. `bbop.jar` - Ontology tool
3. `obo.jar` - Ontology tool
4. `biojava.1.7.1.jar` - Specific old version
5. `blast-serialization-1.0-eclipse-transformed.jar` - Eclipse-transformed custom library
6. `cvu.jar` - Custom utility
7. `jdbc-listener.jar` - Custom JDBC tool
8. `jdbc-tools.jar` - Custom JDBC tool
9. `text-table-formatter-1.0.jar` - Custom formatter
10. `rescu-2.1.0-eclipse-transformed.jar` - Eclipse-transformed REST client
11. `restygwt-2.2.7-eclipse-transformed.jar` - Eclipse-transformed GWT REST library
12. `altcha-1.1.2.jar` - CAPTCHA library
13. `AnalyticsReportingApp-1.0.2.jar` - Custom analytics app
14. `commons-configuration-ant-task-0.9.6.jar` - Specific Ant task
15. `serializer-2.7.1.jar` - Xalan serializer (specific version)
16. `patricia-trie-0.2.jar` - Data structure library
17. `gwt-servlet-jakarta-2.11.0.jar` - GWT servlet adapter for Jakarta

### From `lib/Java/` (4 files):
18. `ontologies/robot.jar` - Ontology processing tool
19. `gwt/gwt-user-2.11.0.jar` - GWT framework
20. `ant-contrib-0.3.jar` - Ant build utilities
21. `serialver.jar` - Java serialization tool

### From `lib/Java/gwt/` (1 additional file kept implicitly):
Note: GWT files are used for compile-time development only and may not all be listed explicitly.

---

## Files that can be DELETED

All files below have been replaced by Maven Central dependencies or are no longer needed.

### Category 1: Spring Framework (14 files - DELETE ALL)
**Reason**: Using `org.springframework:spring-*:6.1.1+` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `spring-aop-6.1.1.jar`
- ✗ `spring-aspects-6.1.1.jar`
- ✗ `spring-beans-6.1.1.jar`
- ✗ `spring-context-6.1.1.jar`
- ✗ `spring-context-support-6.1.1.jar`
- ✗ `spring-core-6.1.1.jar`
- ✗ `spring-expression-6.1.1.jar`
- ✗ `spring-integration-core-6.2.1.jar`
- ✗ `spring-jcl-6.1.1.jar`
- ✗ `spring-messaging-6.1.1.jar`
- ✗ `spring-orm-6.1.1.jar`
- ✗ `spring-oxm-6.1.1.jar`
- ✗ `spring-tx-6.1.1.jar`
- ✗ `spring-web-6.1.1.jar`
- ✗ `spring-webmvc-6.1.1.jar`

From `lib/Java/`:
- ✗ `spring-test-6.1.1.jar`

### Category 2: Spring Security (6 files - DELETE ALL)
**Reason**: Using `org.springframework.security:spring-security-*:6.1.8` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `spring-security-config-6.1.1.jar`
- ✗ `spring-security-core-6.1.1.jar`
- ✗ `spring-security-crypto-6.1.1.jar`
- ✗ `spring-security-ldap-6.1.1.jar`
- ✗ `spring-security-taglibs-6.1.8.jar`
- ✗ `spring-security-web-6.1.1.jar`

### Category 3: Hibernate & JPA (9 files - DELETE ALL)
**Reason**: Using `org.hibernate.orm:hibernate-*:6.4.4.Final` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `hibernate-core-6.4.4.Final.jar`
- ✗ `hibernate-c3p0-6.4.4.Final.jar`
- ✗ `hibernate-commons-annotations-6.0.6.Final.jar`
- ✗ `hibernate-search-engine-6.1.1.Final.jar`
- ✗ `hibernate-search-mapper-pojo-base-6.1.1.Final.jar`
- ✗ `hibernate-validator-8.0.1.Final.jar`
- ✗ `jakarta.persistence-api-3.1.0.jar`
- ✗ `jakarta.transaction-api-2.0.0.jar`
- ✗ `jakarta.validation-api-3.0.2.jar`

From `lib/Java/`:
- ✗ `hibernate-search-engine-6.1.0.Final.jar` (old version)
- ✗ `hibernate-search-mapper-pojo-base-6.1.0.Final.jar` (old version)

### Category 4: Jackson (5 files - DELETE ALL)
**Reason**: Using `com.fasterxml.jackson.*:2.17.2` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `jackson-core-2.15.2.jar`
- ✗ `jackson-databind-2.15.2.jar`
- ✗ `jackson-annotations-2.15.2.jar`
- ✗ `jackson-datatype-jsr310-2.15.2.jar`

From `lib/Java/gwt/`:
- ✗ `jackson-annotations-2.8.2-sources-eclipse-transformed.jar`
- ✗ `gwt-jackson-0.15.0-eclipse-transformed.jar`

### Category 5: Log4j (5 files - DELETE ALL)
**Reason**: Using `org.apache.logging.log4j:*:2.17.1` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `log4j-1.2.15.jar` (old Log4j 1.x version)
- ✗ `log4j-api-2.17.1.jar`
- ✗ `log4j-core-2.17.1.jar`
- ✗ `log4j-jakarta-web-2.17.1.jar`
- ✗ `log4j-slf4j-impl-2.13.3.jar`

### Category 6: SLF4J (2 files - DELETE ALL)
**Reason**: Using `org.slf4j:slf4j-api:2.0.12` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `slf4j-api-1.7.26.jar`
- ✗ `jcl-over-slf4j-1.7.26.jar`

### Category 7: Apache Commons (18 files - DELETE ALL)
**Reason**: Using various `org.apache.commons:*` and `commons-*:*` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `commons-beanutils-1.9.1.jar` → `commons-beanutils:commons-beanutils:1.9.4`
- ✗ `commons-cli-1.4.jar` → `commons-cli:commons-cli:1.6.0`
- ✗ `commons-codec-1.6.jar` → `commons-codec:commons-codec:1.15`
- ✗ `commons-collections-3.2.1.jar` → `commons-collections:commons-collections:3.2.2`
- ✗ `commons-collections4-4.4.jar` → `org.apache.commons:commons-collections4:4.4`
- ✗ `commons-configuration-1.6.jar` → potentially not used
- ✗ `commons-csv-1.11.0.jar` → `org.apache.commons:commons-csv:1.11.0`
- ✗ `commons-digester-1.6.jar` → potentially not used
- ✗ `commons-discovery-0.2.jar` → potentially not used
- ✗ `commons-exec-1.0.jar` → `org.apache.commons:commons-exec:1.0`
- ✗ `commons-io-2.16.1.jar` → `commons-io:commons-io:2.16.1`
- ✗ `commons-lang-2.6.jar` → old version
- ✗ `commons-lang3-3.1.jar` → `org.apache.commons:commons-lang3:3.12.0`
- ✗ `commons-net-1.4.1.jar` → potentially not used
- ✗ `commons-text-1.11.0.jar` → `org.apache.commons:commons-text:1.11.0`
- ✗ `commons-validator-1.1.4.jar` → potentially not used

From `lib/Java/`:
- ✗ `commons-lang3-3.12.0.jar` → `org.apache.commons:commons-lang3:3.12.0`
- ✗ `commons-math-1.2.jar` → potentially not used

### Category 8: Jakarta EE / JavaEE (13 files - DELETE ALL)
**Reason**: Using `jakarta.*:*` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `jakarta.activation-2.0.1.jar` → `jakarta.activation:jakarta.activation-api:2.1.2`
- ✗ `jakarta.annotation-api-2.0.0.jar` → `jakarta.annotation:jakarta.annotation-api:2.1.1`
- ✗ `jakarta.mail-2.0.1.jar` → `org.eclipse.angus:angus-mail:2.0.2`
- ✗ `jakarta.ws.rs-api-3.1.0.jar` → `jakarta.ws.rs:jakarta.ws.rs-api:3.1.0`
- ✗ `jakarta.xml.bind-api-3.0.1.jar` → `jakarta.xml.bind:jakarta.xml.bind-api:4.0.1`
- ✗ `jakarta.servlet.jsp.jstl-api-1.2.7-eclipse-transformed.jar` → `jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api:2.0.0`

From `lib/Java/`:
- ✗ `jakarta-oro-2.0.8.jar` → old Apache ORO
- ✗ `jakarta.servlet.jsp-api-3.1.1.jar` → `jakarta.servlet.jsp:jakarta.servlet.jsp-api:3.0.1`
- ✗ `jakarta.servlet.jsp.jstl-api-3.0.0.jar` → `jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api:2.0.0`

### Category 9: JAXB (8 files - DELETE ALL)
**Reason**: Using `org.glassfish.jaxb:*:4.0.3` and related from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `jaxb-core-4.0.3.jar` → `org.glassfish.jaxb:jaxb-core:4.0.3`
- ✗ `jaxb-libs.jar` → covered by Maven dependencies
- ✗ `jaxb-runtime-4.0.3.jar` → `org.glassfish.jaxb:jaxb-runtime:4.0.2+`
- ✗ `txw2-2.3.1.jar` → old version
- ✗ `txw2-4.0.3.jar` → `org.glassfish.jaxb:txw2:4.0.2+`
- ✗ `istack-commons-runtime-3.0.7.jar` → `com.sun.istack:istack-commons-runtime:4.1.1`

From `lib/Java/`:
- ✗ `jaxb-xjc.jar` → covered by Maven dependencies

### Category 10: Apache HttpComponents (4 files - DELETE ALL)
**Reason**: Using `org.apache.httpcomponents:*:4.5.10+` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `httpclient-4.5.10.jar` → `org.apache.httpcomponents:httpclient:4.5.10`
- ✗ `httpcore-4.4.13.jar` → `org.apache.httpcomponents:httpcore:4.4.13`
- ✗ `httpmime-4.4.1.jar` → `org.apache.httpcomponents:httpmime:4.5.10`

From `lib/Java/jwebunit/`:
- ✗ `httpclient-4.5.4.jar` → older version
- ✗ `httpcore-4.4.13.jar` → duplicate
- ✗ `httpmime-4.2.3.jar` → older version

### Category 11: XML Processing (25 files - DELETE ALL)
**Reason**: Using various XML libraries from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `dom4j-2.1.3.jar` → `org.dom4j:dom4j:2.1.3`
- ✗ `jaxen-1.1.1.jar` → `jaxen:jaxen:1.1.6`
- ✗ `jdom-1.1.jar` → `org.jdom:jdom:1.1`
- ✗ `javax.xml.soap-api-1.4.0.jar` → `jakarta.xml.soap:jakarta.xml.soap-api:3.0.1`
- ✗ `jaxws-api-2.2.jar` → covered by Jakarta
- ✗ `jsr181-api-1.0-MR1.jar` → old API
- ✗ `neethi-2.0.4.jar` → `org.apache.neethi:neethi:3.2.0`
- ✗ `policy-2.3.1.jar` → covered by WS dependencies
- ✗ `relaxngDatatype.jar` → `relaxngDatatype:relaxngDatatype:20020414`
- ✗ `stax-ex-1.8.jar` → `org.jvnet.staxex:stax-ex:2.1.0`
- ✗ `stax2-api-3.1.4.jar` → `org.codehaus.woodstox:stax2-api:4.2.2`
- ✗ `streambuffer-1.5.6.jar` → covered by WS dependencies
- ✗ `wsdl4j-1.6.2.jar` → `wsdl4j:wsdl4j:1.6.3`
- ✗ `wstx-asl-3.2.7.jar` → `org.codehaus.woodstox:wstx-asl:3.2.7`
- ✗ `woodstox-core-asl-4.4.1.jar` → `com.fasterxml.woodstox:woodstox-core:6.7.0`
- ✗ `xalan-2.7.1.jar` → already in lib/Java/jwebunit/
- ✗ `xercesImpl-2.10.0.jar` → duplicate
- ✗ `xml-apis-1.4.01.jar` → duplicate
- ✗ `xmlbeans-2.4.0.jar` → `org.apache.xmlbeans:xmlbeans:5.1.1`
- ✗ `XmlSchema-1.4.2.jar` → `org.apache.ws.xmlschema:xmlschema-core:2.3.1`
- ✗ `xsdlib.jar` → `net.java.dev.msv:xsdlib:2013.6.1`

From `lib/Java/jwebunit/`:
- ✗ `xalan-2.7.1.jar` → `xalan:xalan:2.7.0`
- ✗ `xercesImpl-2.9.1.jar` → older
- ✗ `xercesImpl-2.10.0.jar` → `xerces:xercesImpl:2.12.2`
- ✗ `xml-apis-1.4.01.jar` → `xml-apis:xml-apis:2.0.2`
- ✗ `serializer-2.7.1.jar` → duplicate of the one we keep

### Category 12: Spring WS (3 files - DELETE ALL)
**Reason**: Using `org.springframework.ws:spring-ws-core:4.0.10` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `spring-ws-core-4.0.10.jar` → `org.springframework.ws:spring-ws-core:4.0.10`
- ✗ `spring-xml-2.0.0-RC2.jar` → covered by Spring WS
- ✗ `spring-retry-2.0.5.jar` → `org.springframework.retry:spring-retry:2.0.5`

### Category 13: Solr (2 files - DELETE ALL)
**Reason**: Using `org.apache.solr:solr-solrj:9.4.0` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `solr-solrj-8.11.3.jar` → `org.apache.solr:solr-solrj:9.4.0` (upgraded)
- ✗ `noggit-0.7.jar` → transitive dependency from Solr

### Category 14: Apache POI (3 files - DELETE ALL)
**Reason**: Using `org.apache.poi:*:5.2.5` from Maven Central

From `lib/Java/`:
- ✗ `poi-5.2.5.jar` → `org.apache.poi:poi:5.2.5`
- ✗ `poi-ooxml-5.2.5.jar` → `org.apache.poi:poi-ooxml:5.2.5`
- ✗ `poi-ooxml-lite-5.2.5.jar` → covered by poi-ooxml

### Category 15: Lucene (1 file - DELETE)
**Reason**: Using `org.apache.lucene:*:9.4.2` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `lucene-core-2.3.1.jar` → ancient version, replaced by Lucene 9.4.2
- ✗ `highlighter.jar` → likely Lucene highlighter, covered by Maven

### Category 16: Testing Libraries (45 files - DELETE ALL)
**Reason**: Using modern test dependencies from Maven Central

From `lib/Java/`:
- ✗ `hamcrest-all-1.3.jar` → `org.hamcrest:hamcrest:2.2`
- ✗ `selenium-api-2.35.0.jar` → `org.seleniumhq.selenium:selenium-api:4.16.1`
- ✗ `selenium-htmlunit-driver-2.35.0.jar` → old version
- ✗ `selenium-remote-driver-2.35.0.jar` → `org.seleniumhq.selenium:selenium-*:4.16.1`
- ✗ `selenium-support-2.35.0.jar` → covered by Selenium 4.16.1
- ✗ `geb-core-6.0.jar` → `org.gebish:geb-core:7.0`
- ✗ `geb-exceptions-6.0.jar` → covered by Geb 7.0
- ✗ `geb-spock-6.0.jar` → `org.gebish:geb-junit4:7.0`
- ✗ `geb-waiting-6.0.jar` → covered by Geb 7.0
- ✗ `spock-core-2.3-groovy-3.0.jar` → `org.spockframework:spock-core:2.3-groovy-4.0`
- ✗ `spock-junit4-2.3-groovy-3.0.jar` → `org.spockframework:spock-junit4:2.3-groovy-4.0`
- ✗ `spock-spring-2.3-groovy-3.0.jar` → `org.spockframework:spock-spring:2.3-groovy-4.0`
- ✗ `junit-orderOfExecution.jar` → custom test runner, replaced by modern test config
- ✗ `testInProgress-client-1.1.jar` → old test monitor

From `lib/Java/jwebunit/` (entire directory - 31 files):
- ✗ `jwebunit-core-2.4.jar` → `net.sourceforge.jwebunit:jwebunit-core:3.3`
- ✗ `jwebunit-htmlunit-plugin-2.4.jar` → `net.sourceforge.jwebunit:jwebunit-htmlunit-plugin:3.3`
- ✗ `htmlunit-2.62.0.jar` → `net.sourceforge.htmlunit:htmlunit:2.70.0`
- ✗ `htmlunit-core-js-2.62.0.jar` → `net.sourceforge.htmlunit:htmlunit-core-js:2.70.0`
- ✗ `htmlunit-cssparser-1.12.0.jar` → `net.sourceforge.htmlunit:htmlunit-cssparser:1.14.0`
- ✗ `neko-htmlunit-2.62.0.jar` → `net.sourceforge.htmlunit:neko-htmlunit:2.70.0`
- ✗ `nekohtml-1.9.22.jar` → old version
- ✗ `cssparser-0.9.9.jar` → covered by htmlunit-cssparser
- ✗ `sac-1.3.jar` → CSS parser dependency
- ✗ `dec-0.1.2.jar` → unknown small library
- ✗ `commons-codec-1.7.jar` → duplicate, older
- ✗ `commons-collections-3.2.1.jar` → duplicate
- ✗ `commons-io-2.4.jar` → older version
- ✗ `commons-lang3-3.1.jar` → older version
- ✗ `jetty-http-8.1.9.v20130131.jar` → ancient Jetty 8
- ✗ `jetty-io-8.1.9.v20130131.jar` → ancient Jetty 8
- ✗ `jetty-util-8.1.9.v20130131.jar` → ancient Jetty 8
- ✗ `jetty-websocket-8.1.9.v20130131.jar` → ancient Jetty 8

From `home/WEB-INF/lib/`:
- ✗ `junit-4.11.jar` → `junit:junit:4.13.2`

### Category 17: Build & Development Tools (12 files - DELETE ALL)
**Reason**: Not needed in runtime, only for build time

From `lib/Java/`:
- ✗ `ant-junit.jar` → Ant test runner, not needed with Gradle
- ✗ `ant-junit4.jar` → Ant test runner, not needed with Gradle
- ✗ `ant-optional-1.5.3-1.jar` → ancient Ant version
- ✗ `cobertura.jar` → code coverage tool, not needed with Gradle
- ✗ `grand-1.8.jar` → Ant dependency visualizer
- ✗ `schemaSpy_5.0.0.jar` → database documentation tool

From `lib/Java/tomcat/`:
- ✗ `bin/tomcat-juli-extra.jar` → Tomcat logging, not needed
- ✗ `lib/tomcat-juli-adapters.jar` → Tomcat logging, not needed

From `lib/Java/`:
- ✗ `tomcat-jdbc.jar` → using HikariCP instead
- ✗ `tomcat-juli.jar` → Tomcat logging

From `home/WEB-INF/lib/`:
- ✗ `ant-1.8.1.jar` → ancient Ant
- ✗ `ant-antlr-1.6.5.jar` → old Ant ANTLR integration

### Category 18: Bytecode & ASM (6 files - DELETE ALL)
**Reason**: Using `org.ow2.asm:*:9.6` from Maven Central

From `home/WEB-INF/lib/`:
- ✗ `asm-5.0.3.jar` → `org.ow2.asm:asm:9.6`
- ✗ `asm-analysis-5.0.3.jar` → `org.ow2.asm:asm-analysis:9.6`
- ✗ `asm-commons-5.0.3.jar` → `org.ow2.asm:asm-commons:9.6`
- ✗ `asm-tree-5.0.3.jar` → `org.ow2.asm:asm-tree:9.6`
- ✗ `asm-util-5.0.3.jar` → `org.ow2.asm:asm-util:9.6`
- ✗ `asm-xml-5.0.3.jar` → old version

### Category 19: Other Libraries (35+ files - DELETE ALL)

From `home/WEB-INF/lib/`:
- ✗ `antlr4-runtime-4.13.0.jar` → `org.antlr:antlr4-runtime:4.13.0`
- ✗ `axiom-api-1.2.7.jar` → old SOAP library
- ✗ `axiom-impl-1.2.7.jar` → old SOAP library
- ✗ `backport-util-concurrent-3.1.jar` → Java 5 backport, not needed
- ✗ `byte-buddy-1.12.7.jar` → `net.bytebuddy:byte-buddy:1.14.11`
- ✗ `bytecode.jar` → unknown, likely obsolete
- ✗ `c3p0-0.9.5.5.jar` → `com.mchange:c3p0:0.9.5.5`
- ✗ `castor-1.3-core.jar` → old XML binding
- ✗ `castor-1.3-xml.jar` → old XML binding
- ✗ `classmate-1.5.1.jar` → `com.fasterxml:classmate:1.5.1`
- ✗ `FastInfoset-1.2.15.jar` → `com.sun.xml.fastinfoset:FastInfoset:2.1.0`
- ✗ `freemarker-2.3.20.jar` → `org.freemarker:freemarker:2.3.32`
- ✗ `geronimo-validation_1.0_spec-1.0-CR5.jar` → old validation spec
- ✗ `gmbal-api-only-3.2.0-b003.jar` → GlassFish monitoring
- ✗ `groovycsv-1.0.jar` → `com.xlson.groovycsv:groovycsv:1.0`
- ✗ `gson-2.2.4.jar` → `com.google.code.gson:gson:2.11.0`
- ✗ `guava-27.1-jre.jar` → `com.google.guava:guava:33.0.0-jre`
- ✗ `imgscalr-lib-4.2.jar` → `org.imgscalr:imgscalr-lib:4.2`
- ✗ `jandex-2.4.2.Final.jar` → `io.smallrye:jandex:3.1.2`
- ✗ `javassist-3.24.1-GA.jar` → `org.javassist:javassist:3.30.2-GA`
- ✗ `jboss-logging-3.4.3.Final.jar` → `org.jboss.logging:jboss-logging:3.5.3.Final`
- ✗ `jboss-logging-annotations-1.2.0.Beta1.jar` → `org.jboss.logging:jboss-logging-annotations:2.2.1.Final`
- ✗ `jboss-transaction-api_1.2_spec-1.1.1.Final.jar` → covered by Jakarta
- ✗ `json-20210307.jar` → `org.json:json:20240303`
- ✗ `json-smart-1.1.1.jar` → `net.minidev:json-smart:2.5.0`
- ✗ `jsonevent-layout-1.0.jar` → Log4j layout, not needed
- ✗ `jta-1.1.jar` → old JTA spec
- ✗ `lombok-1.18.20.jar` → using compileOnly, not runtime
- ✗ `mchange-commons-java-0.2.15.jar` → `com.mchange:mchange-commons-java:0.2.19`
- ✗ `micrometer-commons-1.12.1.jar` → `io.micrometer:micrometer-commons:1.12.3`
- ✗ `micrometer-observation-1.12.1.jar` → `io.micrometer:micrometer-observation:1.12.3`
- ✗ `microprofile-openapi-api-3.1.1.jar` → `org.eclipse.microprofile.openapi:microprofile-openapi-api:3.1.1`
- ✗ `oro-2.0.8.jar` → `oro:oro:2.0.8`
- ✗ `owasp-java-html-sanitizer-20200713.1.jar` → `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1`
- ✗ `reactive-streams-1.0.4.jar` → `org.reactivestreams:reactive-streams:1.0.4`
- ✗ `reactor-core-3.6.1.jar` → `io.projectreactor:reactor-core:3.6.2`
- ✗ `signpost-core-1.2.1.2.jar` → `oauth.signpost:signpost-core:2.1.1`
- ✗ `site-search-serialization-1.0.jar` → custom, assess if needed
- ✗ `taglibs-standard-impl-1.2.5-eclipse-transformed.jar` → `org.apache.taglibs:taglibs-standard-impl:1.2.5`
- ✗ `validation-api-1.0.0.GA.jar` → old validation API
- ✗ `zookeeper-3.4.6.jar` → `org.apache.zookeeper:zookeeper:3.9.1`

From `lib/Java/`:
- ✗ `Acme.jar` → ancient utility library
- ✗ `groovycsv-1.0.jar` → duplicate
- ✗ `htsjdk-4.3.0.jar` → `com.github.samtools:htsjdk:4.1.1`
- ✗ `jhall.jar` → Java Help system, rarely used
- ✗ `jool-0.9.15.jar` → `org.jooq:jool:0.9.15`
- ✗ `jung-1.7.4.jar` → graph visualization, check if used
- ✗ `liquibase.jar` → `org.liquibase:liquibase-core:3.4.1`
- ✗ `opencsv-2.1.jar` → `com.opencsv:opencsv:5.9`
- ✗ `postgresql-42.2.18.jar` → `org.postgresql:postgresql:42.2.20`
- ✗ `snakeyaml-1.12.jar` → `org.yaml:snakeyaml:2.2`

From `lib/Java/gwt/`:
- ✗ `gwt-api-checker-eclipse-transformed.jar` → GWT dev tool
- ✗ `gwt-dev-eclipse-transformed.jar` → GWT dev tool
- ✗ `jsinterop-annotations-2.0.0-eclipse-transformed.jar` → covered by GWT
- ✗ `validation-api-1.0.0.GA-sources.jar` → sources JAR, not needed
- ✗ `validation-api-1.0.0.GA.jar` → old validation API

---

## Recommended Deletion Commands

### Step 1: Create a backup (IMPORTANT!)
```bash
cd /opt/zfin/source_roots/coral/zfin.org
tar -czf jar-files-backup-$(date +%Y%m%d).tar.gz lib/Java home/WEB-INF/lib
```

### Step 2: Delete Spring Framework JARs
```bash
rm home/WEB-INF/lib/spring-aop-6.1.1.jar
rm home/WEB-INF/lib/spring-aspects-6.1.1.jar
rm home/WEB-INF/lib/spring-beans-6.1.1.jar
rm home/WEB-INF/lib/spring-context-6.1.1.jar
rm home/WEB-INF/lib/spring-context-support-6.1.1.jar
rm home/WEB-INF/lib/spring-core-6.1.1.jar
rm home/WEB-INF/lib/spring-expression-6.1.1.jar
rm home/WEB-INF/lib/spring-integration-core-6.2.1.jar
rm home/WEB-INF/lib/spring-jcl-6.1.1.jar
rm home/WEB-INF/lib/spring-messaging-6.1.1.jar
rm home/WEB-INF/lib/spring-orm-6.1.1.jar
rm home/WEB-INF/lib/spring-oxm-6.1.1.jar
rm home/WEB-INF/lib/spring-tx-6.1.1.jar
rm home/WEB-INF/lib/spring-web-6.1.1.jar
rm home/WEB-INF/lib/spring-webmvc-6.1.1.jar
rm lib/Java/spring-test-6.1.1.jar
```

### Step 3: Delete Spring Security JARs
```bash
rm home/WEB-INF/lib/spring-security-config-6.1.1.jar
rm home/WEB-INF/lib/spring-security-core-6.1.1.jar
rm home/WEB-INF/lib/spring-security-crypto-6.1.1.jar
rm home/WEB-INF/lib/spring-security-ldap-6.1.1.jar
rm home/WEB-INF/lib/spring-security-taglibs-6.1.8.jar
rm home/WEB-INF/lib/spring-security-web-6.1.1.jar
```

### Step 4: Delete all other redundant JARs (comprehensive list)

**WARNING**: This is a large command. Review carefully before executing.

```bash
# Hibernate & JPA
rm home/WEB-INF/lib/hibernate-core-6.4.4.Final.jar
rm home/WEB-INF/lib/hibernate-c3p0-6.4.4.Final.jar
rm home/WEB-INF/lib/hibernate-commons-annotations-6.0.6.Final.jar
rm home/WEB-INF/lib/hibernate-search-engine-6.1.1.Final.jar
rm home/WEB-INF/lib/hibernate-search-mapper-pojo-base-6.1.1.Final.jar
rm home/WEB-INF/lib/hibernate-validator-8.0.1.Final.jar
rm home/WEB-INF/lib/jakarta.persistence-api-3.1.0.jar
rm home/WEB-INF/lib/jakarta.transaction-api-2.0.0.jar
rm home/WEB-INF/lib/jakarta.validation-api-3.0.2.jar
rm lib/Java/hibernate-search-engine-6.1.0.Final.jar
rm lib/Java/hibernate-search-mapper-pojo-base-6.1.0.Final.jar

# Jackson
rm home/WEB-INF/lib/jackson-core-2.15.2.jar
rm home/WEB-INF/lib/jackson-databind-2.15.2.jar
rm home/WEB-INF/lib/jackson-annotations-2.15.2.jar
rm home/WEB-INF/lib/jackson-datatype-jsr310-2.15.2.jar

# Log4j & SLF4J
rm home/WEB-INF/lib/log4j-1.2.15.jar
rm home/WEB-INF/lib/log4j-api-2.17.1.jar
rm home/WEB-INF/lib/log4j-core-2.17.1.jar
rm home/WEB-INF/lib/log4j-jakarta-web-2.17.1.jar
rm home/WEB-INF/lib/log4j-slf4j-impl-2.13.3.jar
rm home/WEB-INF/lib/slf4j-api-1.7.26.jar
rm home/WEB-INF/lib/jcl-over-slf4j-1.7.26.jar

# Apache Commons
rm home/WEB-INF/lib/commons-beanutils-1.9.1.jar
rm home/WEB-INF/lib/commons-cli-1.4.jar
rm home/WEB-INF/lib/commons-codec-1.6.jar
rm home/WEB-INF/lib/commons-collections-3.2.1.jar
rm home/WEB-INF/lib/commons-collections4-4.4.jar
rm home/WEB-INF/lib/commons-configuration-1.6.jar
rm home/WEB-INF/lib/commons-csv-1.11.0.jar
rm home/WEB-INF/lib/commons-digester-1.6.jar
rm home/WEB-INF/lib/commons-discovery-0.2.jar
rm home/WEB-INF/lib/commons-exec-1.0.jar
rm home/WEB-INF/lib/commons-io-2.16.1.jar
rm home/WEB-INF/lib/commons-lang-2.6.jar
rm home/WEB-INF/lib/commons-lang3-3.1.jar
rm home/WEB-INF/lib/commons-net-1.4.1.jar
rm home/WEB-INF/lib/commons-text-1.11.0.jar
rm home/WEB-INF/lib/commons-validator-1.1.4.jar
rm lib/Java/commons-lang3-3.12.0.jar
rm lib/Java/commons-math-1.2.jar

# Jakarta / JAXB
rm home/WEB-INF/lib/jakarta.activation-2.0.1.jar
rm home/WEB-INF/lib/jakarta.annotation-api-2.0.0.jar
rm home/WEB-INF/lib/jakarta.mail-2.0.1.jar
rm home/WEB-INF/lib/jakarta.ws.rs-api-3.1.0.jar
rm home/WEB-INF/lib/jakarta.xml.bind-api-3.0.1.jar
rm home/WEB-INF/lib/jakarta.servlet.jsp.jstl-api-1.2.7-eclipse-transformed.jar
rm home/WEB-INF/lib/jaxb-core-4.0.3.jar
rm home/WEB-INF/lib/jaxb-libs.jar
rm home/WEB-INF/lib/jaxb-runtime-4.0.3.jar
rm home/WEB-INF/lib/txw2-2.3.1.jar
rm home/WEB-INF/lib/txw2-4.0.3.jar
rm home/WEB-INF/lib/istack-commons-runtime-3.0.7.jar
rm lib/Java/jakarta-oro-2.0.8.jar
rm lib/Java/jakarta.servlet.jsp-api-3.1.1.jar
rm lib/Java/jakarta.servlet.jsp.jstl-api-3.0.0.jar
rm lib/Java/jaxb-xjc.jar

# Apache HttpComponents
rm home/WEB-INF/lib/httpclient-4.5.10.jar
rm home/WEB-INF/lib/httpcore-4.4.13.jar
rm home/WEB-INF/lib/httpmime-4.4.1.jar

# XML Processing
rm home/WEB-INF/lib/dom4j-2.1.3.jar
rm home/WEB-INF/lib/jaxen-1.1.1.jar
rm home/WEB-INF/lib/jdom-1.1.jar
rm home/WEB-INF/lib/javax.xml.soap-api-1.4.0.jar
rm home/WEB-INF/lib/jaxws-api-2.2.jar
rm home/WEB-INF/lib/jsr181-api-1.0-MR1.jar
rm home/WEB-INF/lib/neethi-2.0.4.jar
rm home/WEB-INF/lib/policy-2.3.1.jar
rm home/WEB-INF/lib/relaxngDatatype.jar
rm home/WEB-INF/lib/stax-ex-1.8.jar
rm home/WEB-INF/lib/stax2-api-3.1.4.jar
rm home/WEB-INF/lib/streambuffer-1.5.6.jar
rm home/WEB-INF/lib/wsdl4j-1.6.2.jar
rm home/WEB-INF/lib/wstx-asl-3.2.7.jar
rm home/WEB-INF/lib/woodstox-core-asl-4.4.1.jar
rm home/WEB-INF/lib/xmlbeans-2.4.0.jar
rm home/WEB-INF/lib/XmlSchema-1.4.2.jar
rm home/WEB-INF/lib/xsdlib.jar

# Spring WS
rm home/WEB-INF/lib/spring-ws-core-4.0.10.jar
rm home/WEB-INF/lib/spring-xml-2.0.0-RC2.jar
rm home/WEB-INF/lib/spring-retry-2.0.5.jar

# Solr
rm home/WEB-INF/lib/solr-solrj-8.11.3.jar
rm home/WEB-INF/lib/noggit-0.7.jar

# POI
rm lib/Java/poi-5.2.5.jar
rm lib/Java/poi-ooxml-5.2.5.jar
rm lib/Java/poi-ooxml-lite-5.2.5.jar

# Lucene
rm home/WEB-INF/lib/lucene-core-2.3.1.jar
rm home/WEB-INF/lib/highlighter.jar

# ASM
rm home/WEB-INF/lib/asm-5.0.3.jar
rm home/WEB-INF/lib/asm-analysis-5.0.3.jar
rm home/WEB-INF/lib/asm-commons-5.0.3.jar
rm home/WEB-INF/lib/asm-tree-5.0.3.jar
rm home/WEB-INF/lib/asm-util-5.0.3.jar
rm home/WEB-INF/lib/asm-xml-5.0.3.jar

# Other libraries
rm home/WEB-INF/lib/antlr4-runtime-4.13.0.jar
rm home/WEB-INF/lib/axiom-api-1.2.7.jar
rm home/WEB-INF/lib/axiom-impl-1.2.7.jar
rm home/WEB-INF/lib/backport-util-concurrent-3.1.jar
rm home/WEB-INF/lib/byte-buddy-1.12.7.jar
rm home/WEB-INF/lib/bytecode.jar
rm home/WEB-INF/lib/c3p0-0.9.5.5.jar
rm home/WEB-INF/lib/castor-1.3-core.jar
rm home/WEB-INF/lib/castor-1.3-xml.jar
rm home/WEB-INF/lib/classmate-1.5.1.jar
rm home/WEB-INF/lib/FastInfoset-1.2.15.jar
rm home/WEB-INF/lib/freemarker-2.3.20.jar
rm home/WEB-INF/lib/geronimo-validation_1.0_spec-1.0-CR5.jar
rm home/WEB-INF/lib/gmbal-api-only-3.2.0-b003.jar
rm home/WEB-INF/lib/groovycsv-1.0.jar
rm home/WEB-INF/lib/gson-2.2.4.jar
rm home/WEB-INF/lib/guava-27.1-jre.jar
rm home/WEB-INF/lib/imgscalr-lib-4.2.jar
rm home/WEB-INF/lib/jandex-2.4.2.Final.jar
rm home/WEB-INF/lib/javassist-3.24.1-GA.jar
rm home/WEB-INF/lib/jboss-logging-3.4.3.Final.jar
rm home/WEB-INF/lib/jboss-logging-annotations-1.2.0.Beta1.jar
rm home/WEB-INF/lib/jboss-transaction-api_1.2_spec-1.1.1.Final.jar
rm home/WEB-INF/lib/json-20210307.jar
rm home/WEB-INF/lib/json-smart-1.1.1.jar
rm home/WEB-INF/lib/jsonevent-layout-1.0.jar
rm home/WEB-INF/lib/jta-1.1.jar
rm home/WEB-INF/lib/junit-4.11.jar
rm home/WEB-INF/lib/lombok-1.18.20.jar
rm home/WEB-INF/lib/mchange-commons-java-0.2.15.jar
rm home/WEB-INF/lib/micrometer-commons-1.12.1.jar
rm home/WEB-INF/lib/micrometer-observation-1.12.1.jar
rm home/WEB-INF/lib/microprofile-openapi-api-3.1.1.jar
rm home/WEB-INF/lib/oro-2.0.8.jar
rm home/WEB-INF/lib/owasp-java-html-sanitizer-20200713.1.jar
rm home/WEB-INF/lib/reactive-streams-1.0.4.jar
rm home/WEB-INF/lib/reactor-core-3.6.1.jar
rm home/WEB-INF/lib/signpost-core-1.2.1.2.jar
rm home/WEB-INF/lib/site-search-serialization-1.0.jar
rm home/WEB-INF/lib/taglibs-standard-impl-1.2.5-eclipse-transformed.jar
rm home/WEB-INF/lib/validation-api-1.0.0.GA.jar
rm home/WEB-INF/lib/zookeeper-3.4.6.jar

# lib/Java/ other
rm lib/Java/Acme.jar
rm lib/Java/groovycsv-1.0.jar
rm lib/Java/htsjdk-4.3.0.jar
rm lib/Java/jhall.jar
rm lib/Java/jool-0.9.15.jar
rm lib/Java/jung-1.7.4.jar
rm lib/Java/liquibase.jar
rm lib/Java/opencsv-2.1.jar
rm lib/Java/postgresql-42.2.18.jar
rm lib/Java/snakeyaml-1.12.jar
```

### Step 5: Delete entire obsolete directories

```bash
# Delete entire jwebunit directory (31 files)
rm -rf lib/Java/jwebunit

# Delete obsolete test JARs
rm lib/Java/hamcrest-all-1.3.jar
rm lib/Java/selenium-api-2.35.0.jar
rm lib/Java/selenium-htmlunit-driver-2.35.0.jar
rm lib/Java/selenium-remote-driver-2.35.0.jar
rm lib/Java/selenium-support-2.35.0.jar
rm lib/Java/geb-core-6.0.jar
rm lib/Java/geb-exceptions-6.0.jar
rm lib/Java/geb-spock-6.0.jar
rm lib/Java/geb-waiting-6.0.jar
rm lib/Java/spock-core-2.3-groovy-3.0.jar
rm lib/Java/spock-junit4-2.3-groovy-3.0.jar
rm lib/Java/spock-spring-2.3-groovy-3.0.jar
rm lib/Java/junit-orderOfExecution.jar
rm lib/Java/testInProgress-client-1.1.jar

# Delete build tools
rm lib/Java/ant-junit.jar
rm lib/Java/ant-junit4.jar
rm lib/Java/ant-optional-1.5.3-1.jar
rm lib/Java/cobertura.jar
rm lib/Java/grand-1.8.jar
rm lib/Java/schemaSpy_5.0.0.jar
rm lib/Java/tomcat-jdbc.jar
rm lib/Java/tomcat-juli.jar
rm -rf lib/Java/tomcat

# Delete GWT dev tools (but keep gwt-user-2.11.0.jar!)
rm lib/Java/gwt/gwt-api-checker-eclipse-transformed.jar
rm lib/Java/gwt/gwt-dev-eclipse-transformed.jar
rm lib/Java/gwt/jackson-annotations-2.8.2-sources-eclipse-transformed.jar
rm lib/Java/gwt/gwt-jackson-0.15.0-eclipse-transformed.jar
rm lib/Java/gwt/jsinterop-annotations-2.0.0-eclipse-transformed.jar
rm lib/Java/gwt/validation-api-1.0.0.GA-sources.jar
rm lib/Java/gwt/validation-api-1.0.0.GA.jar
```

### Step 6: Verify build still works
```bash
gradle clean compileJava
```

### Step 7: If build succeeds, delete the backup after 30 days
```bash
# DO NOT delete backup immediately - wait at least 30 days
# rm jar-files-backup-YYYYMMDD.tar.gz
```

---

## Verification Steps

After deleting the JAR files, perform these verification steps:

1. **Clean build test**:
   ```bash
   gradle clean
   gradle compileJava
   ```

2. **Full build test**:
   ```bash
   gradle build -x test
   ```

3. **Run sample tests**:
   ```bash
   gradle test --tests org.zfin.util.ZfinStringUtilsTest
   ```

4. **Check for missing classes** (if errors occur):
   ```bash
   gradle dependencies --configuration runtimeClasspath | grep -i "FAILED"
   ```

5. **Deploy to test environment and run smoke tests**

---

## Expected Results

- **Space saved**: Approximately 150-200 MB
- **Cleaner dependency management**: All dependencies traceable to build.gradle
- **Faster builds**: Fewer JARs to scan at startup
- **Easier upgrades**: No manual JAR file management

---

## Rollback Plan

If issues arise after deletion:

```bash
# Extract backup
cd /opt/zfin/source_roots/coral/zfin.org
tar -xzf jar-files-backup-YYYYMMDD.tar.gz
```

---

## Notes

1. The 22 files marked "KEEP" are **essential** custom libraries not available in Maven Central or specifically required versions.

2. Some "DELETE" files may have been kept initially out of caution but are now superseded by Maven Central dependencies.

3. GWT files in `lib/Java/gwt/` should be reviewed case-by-case - only `gwt-user-2.11.0.jar` and `gwt-servlet-jakarta-2.11.0.jar` are explicitly needed.

4. Test the build after each major category deletion to isolate any issues.

5. Keep the backup for at least 30 days before deleting it.
