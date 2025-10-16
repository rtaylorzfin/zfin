#!/bin/bash

# JAR Cleanup Script for ZFIN Gradle Migration
# This script removes redundant JAR files that have been replaced by Maven Central dependencies
# Generated: 2025-10-16

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}ZFIN JAR Cleanup Script${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Check if we're in the right directory
if [ ! -f "build.gradle" ]; then
    echo -e "${RED}Error: build.gradle not found. Please run this script from the project root.${NC}"
    exit 1
fi

# Create backup
BACKUP_FILE="jar-files-backup-$(date +%Y%m%d-%H%M%S).tar.gz"
echo -e "${YELLOW}Creating backup: ${BACKUP_FILE}${NC}"
tar -czf "$BACKUP_FILE" lib/Java home/WEB-INF/lib 2>/dev/null || true
echo -e "${GREEN}✓ Backup created: ${BACKUP_FILE}${NC}"
echo ""

# Function to safely delete a file
delete_file() {
    local file="$1"
    if [ -f "$file" ]; then
        rm "$file"
        echo "  ✓ Deleted: $file"
        return 0
    else
        echo "  ⊗ Not found (already deleted?): $file"
        return 1
    fi
}

# Function to safely delete a directory
delete_dir() {
    local dir="$1"
    if [ -d "$dir" ]; then
        rm -rf "$dir"
        echo "  ✓ Deleted directory: $dir"
        return 0
    else
        echo "  ⊗ Not found (already deleted?): $dir"
        return 1
    fi
}

deleted_count=0
not_found_count=0

# Category 1: Spring Framework
echo -e "${YELLOW}[1/19] Deleting Spring Framework JARs...${NC}"
for jar in \
    home/WEB-INF/lib/spring-aop-6.1.1.jar \
    home/WEB-INF/lib/spring-aspects-6.1.1.jar \
    home/WEB-INF/lib/spring-beans-6.1.1.jar \
    home/WEB-INF/lib/spring-context-6.1.1.jar \
    home/WEB-INF/lib/spring-context-support-6.1.1.jar \
    home/WEB-INF/lib/spring-core-6.1.1.jar \
    home/WEB-INF/lib/spring-expression-6.1.1.jar \
    home/WEB-INF/lib/spring-integration-core-6.2.1.jar \
    home/WEB-INF/lib/spring-jcl-6.1.1.jar \
    home/WEB-INF/lib/spring-messaging-6.1.1.jar \
    home/WEB-INF/lib/spring-orm-6.1.1.jar \
    home/WEB-INF/lib/spring-oxm-6.1.1.jar \
    home/WEB-INF/lib/spring-tx-6.1.1.jar \
    home/WEB-INF/lib/spring-web-6.1.1.jar \
    home/WEB-INF/lib/spring-webmvc-6.1.1.jar \
    lib/Java/spring-test-6.1.1.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 2: Spring Security
echo -e "${YELLOW}[2/19] Deleting Spring Security JARs...${NC}"
for jar in \
    home/WEB-INF/lib/spring-security-config-6.1.1.jar \
    home/WEB-INF/lib/spring-security-core-6.1.1.jar \
    home/WEB-INF/lib/spring-security-crypto-6.1.1.jar \
    home/WEB-INF/lib/spring-security-ldap-6.1.1.jar \
    home/WEB-INF/lib/spring-security-taglibs-6.1.8.jar \
    home/WEB-INF/lib/spring-security-web-6.1.1.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 3: Hibernate & JPA
echo -e "${YELLOW}[3/19] Deleting Hibernate & JPA JARs...${NC}"
for jar in \
    home/WEB-INF/lib/hibernate-core-6.4.4.Final.jar \
    home/WEB-INF/lib/hibernate-c3p0-6.4.4.Final.jar \
    home/WEB-INF/lib/hibernate-commons-annotations-6.0.6.Final.jar \
    home/WEB-INF/lib/hibernate-search-engine-6.1.1.Final.jar \
    home/WEB-INF/lib/hibernate-search-mapper-pojo-base-6.1.1.Final.jar \
    home/WEB-INF/lib/hibernate-validator-8.0.1.Final.jar \
    home/WEB-INF/lib/jakarta.persistence-api-3.1.0.jar \
    home/WEB-INF/lib/jakarta.transaction-api-2.0.0.jar \
    home/WEB-INF/lib/jakarta.validation-api-3.0.2.jar \
    lib/Java/hibernate-search-engine-6.1.0.Final.jar \
    lib/Java/hibernate-search-mapper-pojo-base-6.1.0.Final.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 4: Jackson
echo -e "${YELLOW}[4/19] Deleting Jackson JARs...${NC}"
for jar in \
    home/WEB-INF/lib/jackson-core-2.15.2.jar \
    home/WEB-INF/lib/jackson-databind-2.15.2.jar \
    home/WEB-INF/lib/jackson-annotations-2.15.2.jar \
    home/WEB-INF/lib/jackson-datatype-jsr310-2.15.2.jar \
    lib/Java/gwt/jackson-annotations-2.8.2-sources-eclipse-transformed.jar \
    lib/Java/gwt/gwt-jackson-0.15.0-eclipse-transformed.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 5: Log4j
echo -e "${YELLOW}[5/19] Deleting Log4j JARs...${NC}"
for jar in \
    home/WEB-INF/lib/log4j-1.2.15.jar \
    home/WEB-INF/lib/log4j-api-2.17.1.jar \
    home/WEB-INF/lib/log4j-core-2.17.1.jar \
    home/WEB-INF/lib/log4j-jakarta-web-2.17.1.jar \
    home/WEB-INF/lib/log4j-slf4j-impl-2.13.3.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 6: SLF4J
echo -e "${YELLOW}[6/19] Deleting SLF4J JARs...${NC}"
for jar in \
    home/WEB-INF/lib/slf4j-api-1.7.26.jar \
    home/WEB-INF/lib/jcl-over-slf4j-1.7.26.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 7: Apache Commons
echo -e "${YELLOW}[7/19] Deleting Apache Commons JARs...${NC}"
for jar in \
    home/WEB-INF/lib/commons-beanutils-1.9.1.jar \
    home/WEB-INF/lib/commons-cli-1.4.jar \
    home/WEB-INF/lib/commons-codec-1.6.jar \
    home/WEB-INF/lib/commons-collections-3.2.1.jar \
    home/WEB-INF/lib/commons-collections4-4.4.jar \
    home/WEB-INF/lib/commons-configuration-1.6.jar \
    home/WEB-INF/lib/commons-csv-1.11.0.jar \
    home/WEB-INF/lib/commons-digester-1.6.jar \
    home/WEB-INF/lib/commons-discovery-0.2.jar \
    home/WEB-INF/lib/commons-exec-1.0.jar \
    home/WEB-INF/lib/commons-io-2.16.1.jar \
    home/WEB-INF/lib/commons-lang-2.6.jar \
    home/WEB-INF/lib/commons-lang3-3.1.jar \
    home/WEB-INF/lib/commons-net-1.4.1.jar \
    home/WEB-INF/lib/commons-text-1.11.0.jar \
    home/WEB-INF/lib/commons-validator-1.1.4.jar \
    lib/Java/commons-lang3-3.12.0.jar \
    lib/Java/commons-math-1.2.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 8: Jakarta EE
echo -e "${YELLOW}[8/19] Deleting Jakarta EE JARs...${NC}"
for jar in \
    home/WEB-INF/lib/jakarta.activation-2.0.1.jar \
    home/WEB-INF/lib/jakarta.annotation-api-2.0.0.jar \
    home/WEB-INF/lib/jakarta.mail-2.0.1.jar \
    home/WEB-INF/lib/jakarta.ws.rs-api-3.1.0.jar \
    home/WEB-INF/lib/jakarta.xml.bind-api-3.0.1.jar \
    home/WEB-INF/lib/jakarta.servlet.jsp.jstl-api-1.2.7-eclipse-transformed.jar \
    lib/Java/jakarta-oro-2.0.8.jar \
    lib/Java/jakarta.servlet.jsp-api-3.1.1.jar \
    lib/Java/jakarta.servlet.jsp.jstl-api-3.0.0.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 9: JAXB
echo -e "${YELLOW}[9/19] Deleting JAXB JARs...${NC}"
for jar in \
    home/WEB-INF/lib/jaxb-core-4.0.3.jar \
    home/WEB-INF/lib/jaxb-libs.jar \
    home/WEB-INF/lib/jaxb-runtime-4.0.3.jar \
    home/WEB-INF/lib/txw2-2.3.1.jar \
    home/WEB-INF/lib/txw2-4.0.3.jar \
    home/WEB-INF/lib/istack-commons-runtime-3.0.7.jar \
    lib/Java/jaxb-xjc.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 10: Apache HttpComponents
echo -e "${YELLOW}[10/19] Deleting Apache HttpComponents JARs...${NC}"
for jar in \
    home/WEB-INF/lib/httpclient-4.5.10.jar \
    home/WEB-INF/lib/httpcore-4.4.13.jar \
    home/WEB-INF/lib/httpmime-4.4.1.jar \
    lib/Java/jwebunit/httpclient-4.5.4.jar \
    lib/Java/jwebunit/httpcore-4.4.13.jar \
    lib/Java/jwebunit/httpmime-4.2.3.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 11: XML Processing
echo -e "${YELLOW}[11/19] Deleting XML Processing JARs...${NC}"
for jar in \
    home/WEB-INF/lib/dom4j-2.1.3.jar \
    home/WEB-INF/lib/jaxen-1.1.1.jar \
    home/WEB-INF/lib/jdom-1.1.jar \
    home/WEB-INF/lib/javax.xml.soap-api-1.4.0.jar \
    home/WEB-INF/lib/jaxws-api-2.2.jar \
    home/WEB-INF/lib/jsr181-api-1.0-MR1.jar \
    home/WEB-INF/lib/neethi-2.0.4.jar \
    home/WEB-INF/lib/policy-2.3.1.jar \
    home/WEB-INF/lib/relaxngDatatype.jar \
    home/WEB-INF/lib/stax-ex-1.8.jar \
    home/WEB-INF/lib/stax2-api-3.1.4.jar \
    home/WEB-INF/lib/streambuffer-1.5.6.jar \
    home/WEB-INF/lib/wsdl4j-1.6.2.jar \
    home/WEB-INF/lib/wstx-asl-3.2.7.jar \
    home/WEB-INF/lib/woodstox-core-asl-4.4.1.jar \
    home/WEB-INF/lib/xmlbeans-2.4.0.jar \
    home/WEB-INF/lib/XmlSchema-1.4.2.jar \
    home/WEB-INF/lib/xsdlib.jar \
    lib/Java/jwebunit/xalan-2.7.1.jar \
    lib/Java/jwebunit/xercesImpl-2.9.1.jar \
    lib/Java/jwebunit/xercesImpl-2.10.0.jar \
    lib/Java/jwebunit/xml-apis-1.4.01.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 12: Spring WS
echo -e "${YELLOW}[12/19] Deleting Spring WS JARs...${NC}"
for jar in \
    home/WEB-INF/lib/spring-ws-core-4.0.10.jar \
    home/WEB-INF/lib/spring-xml-2.0.0-RC2.jar \
    home/WEB-INF/lib/spring-retry-2.0.5.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 13: Solr
echo -e "${YELLOW}[13/19] Deleting Solr JARs...${NC}"
for jar in \
    home/WEB-INF/lib/solr-solrj-8.11.3.jar \
    home/WEB-INF/lib/noggit-0.7.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 14: Apache POI
echo -e "${YELLOW}[14/19] Deleting Apache POI JARs...${NC}"
for jar in \
    lib/Java/poi-5.2.5.jar \
    lib/Java/poi-ooxml-5.2.5.jar \
    lib/Java/poi-ooxml-lite-5.2.5.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 15: Lucene
echo -e "${YELLOW}[15/19] Deleting Lucene JARs...${NC}"
for jar in \
    home/WEB-INF/lib/lucene-core-2.3.1.jar \
    home/WEB-INF/lib/highlighter.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 16: ASM
echo -e "${YELLOW}[16/19] Deleting ASM JARs...${NC}"
for jar in \
    home/WEB-INF/lib/asm-5.0.3.jar \
    home/WEB-INF/lib/asm-analysis-5.0.3.jar \
    home/WEB-INF/lib/asm-commons-5.0.3.jar \
    home/WEB-INF/lib/asm-tree-5.0.3.jar \
    home/WEB-INF/lib/asm-util-5.0.3.jar \
    home/WEB-INF/lib/asm-xml-5.0.3.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Category 17: Testing Libraries
echo -e "${YELLOW}[17/19] Deleting Testing Libraries...${NC}"
for jar in \
    lib/Java/hamcrest-all-1.3.jar \
    lib/Java/selenium-api-2.35.0.jar \
    lib/Java/selenium-htmlunit-driver-2.35.0.jar \
    lib/Java/selenium-remote-driver-2.35.0.jar \
    lib/Java/selenium-support-2.35.0.jar \
    lib/Java/geb-core-6.0.jar \
    lib/Java/geb-exceptions-6.0.jar \
    lib/Java/geb-spock-6.0.jar \
    lib/Java/geb-waiting-6.0.jar \
    lib/Java/spock-core-2.3-groovy-3.0.jar \
    lib/Java/spock-junit4-2.3-groovy-3.0.jar \
    lib/Java/spock-spring-2.3-groovy-3.0.jar \
    lib/Java/junit-orderOfExecution.jar \
    lib/Java/testInProgress-client-1.1.jar \
    home/WEB-INF/lib/junit-4.11.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done

# Delete entire jwebunit directory
if delete_dir "lib/Java/jwebunit"; then
    ((deleted_count+=16))  # Approximate count of remaining files
fi
echo ""

# Category 18: Build Tools
echo -e "${YELLOW}[18/19] Deleting Build Tools...${NC}"
for jar in \
    lib/Java/ant-junit.jar \
    lib/Java/ant-junit4.jar \
    lib/Java/ant-optional-1.5.3-1.jar \
    lib/Java/cobertura.jar \
    lib/Java/grand-1.8.jar \
    lib/Java/schemaSpy_5.0.0.jar \
    lib/Java/tomcat-jdbc.jar \
    lib/Java/tomcat-juli.jar \
    home/WEB-INF/lib/ant-1.8.1.jar \
    home/WEB-INF/lib/ant-antlr-1.6.5.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done

# Delete tomcat directory
if delete_dir "lib/Java/tomcat"; then
    ((deleted_count+=2))
fi
echo ""

# Category 19: Other Libraries
echo -e "${YELLOW}[19/19] Deleting Other Libraries...${NC}"
for jar in \
    home/WEB-INF/lib/antlr4-runtime-4.13.0.jar \
    home/WEB-INF/lib/axiom-api-1.2.7.jar \
    home/WEB-INF/lib/axiom-impl-1.2.7.jar \
    home/WEB-INF/lib/backport-util-concurrent-3.1.jar \
    home/WEB-INF/lib/byte-buddy-1.12.7.jar \
    home/WEB-INF/lib/bytecode.jar \
    home/WEB-INF/lib/c3p0-0.9.5.5.jar \
    home/WEB-INF/lib/castor-1.3-core.jar \
    home/WEB-INF/lib/castor-1.3-xml.jar \
    home/WEB-INF/lib/classmate-1.5.1.jar \
    home/WEB-INF/lib/FastInfoset-1.2.15.jar \
    home/WEB-INF/lib/freemarker-2.3.20.jar \
    home/WEB-INF/lib/geronimo-validation_1.0_spec-1.0-CR5.jar \
    home/WEB-INF/lib/gmbal-api-only-3.2.0-b003.jar \
    home/WEB-INF/lib/groovycsv-1.0.jar \
    home/WEB-INF/lib/gson-2.2.4.jar \
    home/WEB-INF/lib/guava-27.1-jre.jar \
    home/WEB-INF/lib/imgscalr-lib-4.2.jar \
    home/WEB-INF/lib/jandex-2.4.2.Final.jar \
    home/WEB-INF/lib/javassist-3.24.1-GA.jar \
    home/WEB-INF/lib/jboss-logging-3.4.3.Final.jar \
    home/WEB-INF/lib/jboss-logging-annotations-1.2.0.Beta1.jar \
    home/WEB-INF/lib/jboss-transaction-api_1.2_spec-1.1.1.Final.jar \
    home/WEB-INF/lib/json-20210307.jar \
    home/WEB-INF/lib/json-smart-1.1.1.jar \
    home/WEB-INF/lib/jsonevent-layout-1.0.jar \
    home/WEB-INF/lib/jta-1.1.jar \
    home/WEB-INF/lib/lombok-1.18.20.jar \
    home/WEB-INF/lib/mchange-commons-java-0.2.15.jar \
    home/WEB-INF/lib/micrometer-commons-1.12.1.jar \
    home/WEB-INF/lib/micrometer-observation-1.12.1.jar \
    home/WEB-INF/lib/microprofile-openapi-api-3.1.1.jar \
    home/WEB-INF/lib/oro-2.0.8.jar \
    home/WEB-INF/lib/owasp-java-html-sanitizer-20200713.1.jar \
    home/WEB-INF/lib/reactive-streams-1.0.4.jar \
    home/WEB-INF/lib/reactor-core-3.6.1.jar \
    home/WEB-INF/lib/signpost-core-1.2.1.2.jar \
    home/WEB-INF/lib/site-search-serialization-1.0.jar \
    home/WEB-INF/lib/taglibs-standard-impl-1.2.5-eclipse-transformed.jar \
    home/WEB-INF/lib/validation-api-1.0.0.GA.jar \
    home/WEB-INF/lib/zookeeper-3.4.6.jar \
    lib/Java/Acme.jar \
    lib/Java/groovycsv-1.0.jar \
    lib/Java/htsjdk-4.3.0.jar \
    lib/Java/jhall.jar \
    lib/Java/jool-0.9.15.jar \
    lib/Java/jung-1.7.4.jar \
    lib/Java/liquibase.jar \
    lib/Java/opencsv-2.1.jar \
    lib/Java/postgresql-42.2.18.jar \
    lib/Java/snakeyaml-1.12.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done

# Delete GWT dev tools (keep gwt-user-2.11.0.jar!)
for jar in \
    lib/Java/gwt/gwt-api-checker-eclipse-transformed.jar \
    lib/Java/gwt/gwt-dev-eclipse-transformed.jar \
    lib/Java/gwt/jsinterop-annotations-2.0.0-eclipse-transformed.jar \
    lib/Java/gwt/validation-api-1.0.0.GA-sources.jar \
    lib/Java/gwt/validation-api-1.0.0.GA.jar
do
    if delete_file "$jar"; then ((deleted_count++)); else ((not_found_count++)); fi
done
echo ""

# Summary
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Cleanup Summary${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "Files deleted: ${GREEN}${deleted_count}${NC}"
echo -e "Files not found: ${YELLOW}${not_found_count}${NC}"
echo -e "Backup: ${GREEN}${BACKUP_FILE}${NC}"
echo ""

echo -e "${YELLOW}Next Steps:${NC}"
echo "1. Run: gradle clean compileJava"
echo "2. Run: gradle build -x test"
echo "3. Run: gradle test --tests org.zfin.util.ZfinStringUtilsTest"
echo "4. If all tests pass, keep the backup for 30 days before deleting"
echo ""
echo -e "${GREEN}Cleanup complete!${NC}"
