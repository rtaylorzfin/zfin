# Custom JAR Files - Non-Maven Central Libraries

## Overview

This document identifies JAR files that are **not available in Maven Central** and require special handling in the Gradle build configuration. These will be managed as file dependencies using Gradle's `files()` directive.

**Total Custom JARs**: 19 files
**Date**: 2025-10-16

---

## Category 1: Confirmed Custom Tools (File Dependencies)

These are proprietary or custom tools that will remain in the filesystem and be referenced as file dependencies.

### 1. agr_curation_api.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: Alliance of Genome Resources (AGR) curation API tool
- **Status**: Custom tool - not publicly available
- **Priority**: HIGH
- **Gradle Strategy**:
  ```groovy
  implementation files('home/WEB-INF/lib/agr_curation_api.jar')
  ```

### 2. bbop.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: Bioinformatics ontology tool
- **Status**: Custom tool - not publicly available
- **Priority**: MEDIUM
- **Gradle Strategy**:
  ```groovy
  implementation files('home/WEB-INF/lib/bbop.jar')
  ```

### 3. obo.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: Ontology tool (OBO format handling)
- **Status**: Custom tool - not publicly available
- **Priority**: HIGH
- **Gradle Strategy**:
  ```groovy
  implementation files('home/WEB-INF/lib/obo.jar')
  ```

### 4. robot.jar
- **Location**: `lib/Java/ontologies/`
- **Version**: Unknown
- **Purpose**: ROBOT (Recursive Ontology Build and Integration Tool) - ontology processing
- **Status**: Custom tool - not publicly available
- **Priority**: HIGH
- **Gradle Strategy**:
  ```groovy
  implementation files('lib/Java/ontologies/robot.jar')
  ```

---

## Category 2: Eclipse-Transformed JARs

These are Maven Central libraries that have been transformed with Eclipse tools (likely for GWT compatibility or similar needs).

### 5. blast-serialization-1.0-eclipse-transformed.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: 1.0
- **Purpose**: BLAST serialization (Eclipse transformed)
- **Priority**: HIGH
- **Investigation Needed**: Check if original Maven library can be used, or if Eclipse transformation is required
- **Gradle Strategy**:
  ```groovy
  implementation files('home/WEB-INF/lib/blast-serialization-1.0-eclipse-transformed.jar')
  ```

### 6. rescu-2.1.0-eclipse-transformed.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: 2.1.0
- **Original**: `com.github.mmazi:rescu:2.1.0` (likely)
- **Priority**: MEDIUM
- **Investigation Needed**: Determine if Eclipse transformation is necessary
- **Gradle Strategy**:
  ```groovy
  // Try original first:
  // implementation 'com.github.mmazi:rescu:2.1.0'
  // If transformation is required:
  implementation files('home/WEB-INF/lib/rescu-2.1.0-eclipse-transformed.jar')
  ```

### 7. restygwt-2.2.7-eclipse-transformed.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: 2.2.7
- **Original**: `org.fusesource.restygwt:restygwt:2.2.7` (likely)
- **Priority**: MEDIUM
- **Investigation Needed**: Determine if Eclipse transformation is necessary
- **Gradle Strategy**:
  ```groovy
  // Try original first:
  // implementation 'org.fusesource.restygwt:restygwt:2.2.7'
  // If transformation is required:
  implementation files('home/WEB-INF/lib/restygwt-2.2.7-eclipse-transformed.jar')
  ```

---

## Category 3: Unknown Custom JARs (Requires Investigation)

These files need further investigation to determine their origin and whether Maven Central alternatives exist.

### 8. AnalyticsReportingApp-1.0.2.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: 1.0.2
- **Purpose**: Analytics reporting application
- **Priority**: MEDIUM
- **Investigation**: Likely an internal/custom application
- **Gradle Strategy**: File dependency

### 9. bytecode.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: Bytecode manipulation/analysis
- **Priority**: MEDIUM
- **Investigation**: May be replaceable with ASM or similar Maven library
- **Gradle Strategy**: File dependency or Maven replacement

### 10. cvu.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: Controlled Vocabulary Utility (likely)
- **Priority**: MEDIUM
- **Investigation**: Likely custom ZFIN tool
- **Gradle Strategy**: File dependency

### 11. highlighter.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: Text highlighting (syntax/search)
- **Priority**: LOW
- **Investigation**: Check if Maven alternative exists
- **Gradle Strategy**: File dependency or Maven replacement

### 12. jaxb-libs.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: JAXB libraries bundle
- **Priority**: MEDIUM
- **Investigation**: Likely can be replaced with individual JAXB Maven dependencies
- **Recommended Maven Alternative**:
  ```groovy
  implementation 'jakarta.xml.bind:jakarta.xml.bind-api:3.0.1'
  implementation 'com.sun.xml.bind:jaxb-impl:3.0.2'
  ```

### 13. jdbc-listener.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: JDBC event listener
- **Priority**: MEDIUM
- **Investigation**: Likely custom ZFIN utility
- **Gradle Strategy**: File dependency

### 14. jdbc-tools.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: Unknown
- **Purpose**: JDBC utilities
- **Priority**: MEDIUM
- **Investigation**: Likely custom ZFIN utility
- **Gradle Strategy**: File dependency

### 15. jsonevent-layout-1.0.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: 1.0
- **Purpose**: JSON event layout (likely for logging)
- **Priority**: LOW
- **Investigation**: Check for Log4j/Logback JSON layout in Maven Central
- **Gradle Strategy**: File dependency or Maven replacement

### 16. site-search-serialization-1.0.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: 1.0
- **Purpose**: Site search serialization
- **Priority**: MEDIUM
- **Investigation**: Likely custom ZFIN tool
- **Gradle Strategy**: File dependency

### 17. text-table-formatter-1.0.jar
- **Location**: `home/WEB-INF/lib/`
- **Version**: 1.0
- **Purpose**: Text table formatting
- **Priority**: LOW
- **Investigation**: Check for Maven alternatives (e.g., `org.nocrala.tools:text-table-formatter`)
- **Possible Maven Alternative**: `org.nocrala.tools:text-table-formatter:1.1`
- **Gradle Strategy**: File dependency or Maven replacement

### 18. Acme.jar
- **Location**: `lib/Java/`
- **Version**: Unknown
- **Purpose**: Unknown (possibly Acme Laboratories utilities)
- **Priority**: LOW
- **Investigation**: Determine usage and purpose
- **Gradle Strategy**: File dependency

### 19. testInProgress-client-1.1.jar
- **Location**: `lib/Java/`
- **Version**: 1.1
- **Purpose**: Test progress monitoring client
- **Priority**: LOW
- **Scope**: Test only
- **Investigation**: Determine if still used
- **Gradle Strategy**:
  ```groovy
  testImplementation files('lib/Java/testInProgress-client-1.1.jar')
  ```

---

## Implementation Strategy

### Phase 2.4 Approach

1. **Confirmed Custom Tools (4 JARs)**
   - Use `files()` dependency in Gradle
   - Keep JARs in current filesystem locations
   - Document purpose and usage

2. **Eclipse-Transformed JARs (3 JARs)**
   - Test if original Maven Central versions work
   - If transformation is required, use `files()` dependency
   - Document reason for using transformed version

3. **Unknown Custom JARs (12 JARs)**
   - Investigate each JAR's manifest and usage
   - Attempt to find Maven Central alternatives
   - For truly custom tools, use `files()` dependency
   - Consider refactoring to remove unused JARs

### Gradle Configuration Pattern

```groovy
dependencies {
    // Maven Central dependencies
    implementation 'org.hibernate.orm:hibernate-core:6.4.4.Final'
    implementation 'org.springframework:spring-core:6.1.1'
    // ... other Maven dependencies

    // Custom file dependencies - AGR/Ontology tools
    implementation files(
        'home/WEB-INF/lib/agr_curation_api.jar',
        'home/WEB-INF/lib/bbop.jar',
        'home/WEB-INF/lib/obo.jar'
    )

    implementation files('lib/Java/ontologies/robot.jar')

    // Custom file dependencies - Eclipse transformed (if required)
    implementation files(
        'home/WEB-INF/lib/blast-serialization-1.0-eclipse-transformed.jar',
        'home/WEB-INF/lib/rescu-2.1.0-eclipse-transformed.jar',
        'home/WEB-INF/lib/restygwt-2.2.7-eclipse-transformed.jar'
    )

    // Custom file dependencies - ZFIN utilities (needs investigation)
    implementation files(
        'home/WEB-INF/lib/AnalyticsReportingApp-1.0.2.jar',
        'home/WEB-INF/lib/bytecode.jar',
        'home/WEB-INF/lib/cvu.jar',
        'home/WEB-INF/lib/jdbc-listener.jar',
        'home/WEB-INF/lib/jdbc-tools.jar',
        'home/WEB-INF/lib/site-search-serialization-1.0.jar'
    )

    // Low priority custom dependencies
    implementation files(
        'home/WEB-INF/lib/highlighter.jar',
        'home/WEB-INF/lib/jsonevent-layout-1.0.jar',
        'home/WEB-INF/lib/text-table-formatter-1.0.jar',
        'lib/Java/Acme.jar'
    )

    // Test dependencies
    testImplementation files('lib/Java/testInProgress-client-1.1.jar')
}
```

---

## Investigation Checklist for Phase 2.4

- [ ] Extract and examine manifest files for unknown JARs
- [ ] Search codebase for usage of each custom JAR
- [ ] Determine if Eclipse transformations are still necessary
- [ ] Test Maven alternatives for replaceable JARs
- [ ] Document reason for keeping each custom JAR
- [ ] Consider creating local Maven repository for custom JARs
- [ ] Update .gitignore to preserve custom JAR locations

---

## Notes

1. **Version Control**: Custom JARs will remain in version control since they're not available from Maven Central
2. **Documentation**: Each custom JAR should be documented with its purpose and source
3. **Future Cleanup**: Some custom JARs may be unused and can be removed after testing
4. **Transitive Dependencies**: Custom JARs may have their own dependencies that need to be managed separately

---

**Next Steps**:
- Complete Phase 1.5 (dependency tree analysis)
- Move to Phase 2.4 for detailed investigation of each custom JAR
- Test POC build with subset of dependencies including some custom JARs
