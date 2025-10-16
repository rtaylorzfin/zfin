# Dependency Conflicts and Version Analysis

## Overview

Analysis of version conflicts, duplicates, and transitive dependency issues discovered during JAR inventory for Gradle migration.

**Analysis Date**: 2025-10-16
**Total Dependencies Analyzed**: 247 JARs

---

## Critical Findings Summary

| Category | Count | Risk Level |
|----------|-------|------------|
| Version Conflicts | 12 libraries | MEDIUM-HIGH |
| Exact Duplicates | 4 libraries | LOW |
| Test-Only Duplicates | 8 libraries | LOW |
| Framework Mismatches | 1 (Spring Security) | MEDIUM |

---

## 1. Version Conflicts (Same Library, Different Versions)

### 1.1 commons-lang3 (HIGH Priority)
- **home/WEB-INF/lib**: `commons-lang3-3.1.jar` (2012 - very old)
- **lib/Java**: `commons-lang3-3.12.0.jar` (2021)
- **lib/Java/jwebunit**: `commons-lang3-3.1.jar` (duplicate)
- **Impact**: Major version gap (3.1 → 3.12.0), potential API compatibility issues
- **Recommendation**: Use **3.12.0** (latest stable) for all scopes
- **Gradle Resolution**:
  ```groovy
  implementation 'org.apache.commons:commons-lang3:3.12.0'
  // Test dependencies will inherit 3.12.0
  ```

### 1.2 hibernate-search (HIGH Priority)
- **home/WEB-INF/lib**: `hibernate-search-*-6.1.1.Final.jar`
  - hibernate-search-engine-6.1.1.Final.jar
  - hibernate-search-mapper-pojo-base-6.1.1.Final.jar
- **lib/Java**: `hibernate-search-*-6.1.0.Final.jar`
  - hibernate-search-engine-6.1.0.Final.jar
  - hibernate-search-mapper-pojo-base-6.1.0.Final.jar
- **Impact**: Minor version difference (6.1.0 → 6.1.1)
- **Note**: Must match Hibernate Core version (6.4.4.Final)
- **Recommendation**: Use **6.1.1.Final** (aligns with Hibernate 6.4.4)
- **Gradle Resolution**:
  ```groovy
  implementation 'org.hibernate.search:hibernate-search-engine:6.1.1.Final'
  implementation 'org.hibernate.search:hibernate-search-mapper-pojo-base:6.1.1.Final'
  ```

### 1.3 validation-api (MEDIUM Priority - Namespace Change)
- **home/WEB-INF/lib**: `jakarta.validation-api-3.0.2.jar` (Jakarta EE 9+)
- **home/WEB-INF/lib**: `validation-api-1.0.0.GA.jar` (javax namespace - legacy)
- **lib/Java/gwt**: `validation-api-1.0.0.GA.jar` + sources
- **Impact**: Namespace change (`javax.validation` → `jakarta.validation`)
- **Analysis**: This is a **major breaking change** (Java EE → Jakarta EE)
- **Recommendation**:
  - Use **jakarta.validation-api:3.0.2** for main application
  - Keep **javax.validation-api:1.0.0.GA** only if GWT requires it
  - May require code changes if using `javax.validation.*` imports
- **Gradle Resolution**:
  ```groovy
  implementation 'jakarta.validation:jakarta.validation-api:3.0.2'
  // Only if GWT compatibility required:
  compileOnly 'javax.validation:validation-api:1.0.0.GA'
  ```

### 1.4 httpclient (MEDIUM Priority)
- **home/WEB-INF/lib**: `httpclient-4.5.10.jar` (2019)
- **lib/Java/jwebunit**: `httpclient-4.5.4.jar` (2017 - test only)
- **Impact**: Minor version difference, test scope only
- **Recommendation**: Use **4.5.10** for all scopes
- **Gradle Resolution**:
  ```groovy
  implementation 'org.apache.httpcomponents:httpclient:4.5.10'
  // Test will inherit 4.5.10
  ```

### 1.5 httpmime (MEDIUM Priority)
- **home/WEB-INF/lib**: `httpmime-4.4.1.jar` (2015)
- **lib/Java/jwebunit**: `httpmime-4.2.3.jar` (2012 - test only)
- **Impact**: Should align with httpclient version
- **Note**: Version mismatch with httpclient (4.5.10 vs 4.4.1)
- **Recommendation**: **Upgrade to 4.5.10** to match httpclient
- **Gradle Resolution**:
  ```groovy
  implementation 'org.apache.httpcomponents:httpmime:4.5.10'
  ```

### 1.6 httpcore (LOW Priority)
- **home/WEB-INF/lib**: `httpcore-4.4.13.jar`
- **lib/Java/jwebunit**: `httpcore-4.4.13.jar` (same version)
- **Impact**: None - same version
- **Recommendation**: Continue using **4.4.13**
- **Gradle Resolution**: Will dedupe automatically

### 1.7 commons-codec (LOW Priority)
- **home/WEB-INF/lib**: `commons-codec-1.6.jar` (2011 - very old)
- **lib/Java/jwebunit**: `commons-codec-1.7.jar` (2012 - test only)
- **Impact**: Very minor version difference
- **Recommendation**: **Upgrade to 1.15** (current stable as of 2021)
- **Gradle Resolution**:
  ```groovy
  implementation 'commons-codec:commons-codec:1.15'
  ```

### 1.8 commons-io (LOW Priority)
- **home/WEB-INF/lib**: `commons-io-2.16.1.jar` (latest)
- **lib/Java/jwebunit**: `commons-io-2.4.jar` (2012 - test only)
- **Impact**: Test scope only, main version is current
- **Recommendation**: Use **2.16.1** for all scopes
- **Gradle Resolution**: Will dedupe automatically

### 1.9 commons-collections vs commons-collections4 (MEDIUM Priority)
- **home/WEB-INF/lib**: `commons-collections-3.2.1.jar` (legacy)
- **home/WEB-INF/lib**: `commons-collections4-4.4.jar` (modern)
- **lib/Java/jwebunit**: `commons-collections-3.2.1.jar` (duplicate)
- **Impact**: Different groupId - these are **separate libraries**
  - `commons-collections:commons-collections` (v3 - legacy, unmaintained)
  - `org.apache.commons:commons-collections4` (v4 - current)
- **Analysis**: Both may be needed if different code uses different APIs
- **Recommendation**:
  - Keep both if codebase uses v3 API
  - Migrate to collections4 long-term
- **Gradle Resolution**:
  ```groovy
  implementation 'commons-collections:commons-collections:3.2.1'
  implementation 'org.apache.commons:commons-collections4:4.4'
  ```

### 1.10 jackson-annotations (LOW Priority)
- **home/WEB-INF/lib**: `jackson-annotations-2.15.2.jar` (2023)
- **lib/Java/gwt**: `jackson-annotations-2.8.2-sources-eclipse-transformed.jar` (sources only)
- **Impact**: Sources JAR, different purpose
- **Recommendation**: Use **2.15.2** for main, sources may not be needed
- **Gradle Resolution**:
  ```groovy
  implementation 'com.fasterxml.jackson.core:jackson-annotations:2.15.2'
  ```

### 1.11 xercesImpl (LOW Priority - Test Only)
- **lib/Java/jwebunit**: `xercesImpl-2.10.0.jar`
- **lib/Java/jwebunit**: `xercesImpl-2.9.1.jar`
- **Impact**: Test scope only, both old versions
- **Recommendation**: Use **2.10.0** (newer), or upgrade to 2.12.2
- **Gradle Resolution**:
  ```groovy
  testImplementation 'xerces:xercesImpl:2.12.2'
  ```

### 1.12 groovycsv (LOW Priority)
- **home/WEB-INF/lib**: `groovycsv-1.0.jar`
- **lib/Java**: `groovycsv-1.0.jar` (exact duplicate)
- **Impact**: None - exact duplicate
- **Recommendation**: Single dependency
- **Gradle Resolution**: Will dedupe automatically

---

## 2. Framework Version Consistency

### 2.1 Spring Framework (MOSTLY CONSISTENT ✓)
All Spring Core modules at **6.1.1**:
- spring-core, spring-beans, spring-context, spring-web, etc.

**Exception**:
- spring-security-taglibs: **6.1.8** (vs 6.1.1 for other security modules)

**Analysis**: Minor version mismatch in Spring Security
- Most Spring Security modules: 6.1.1
- spring-security-taglibs: 6.1.8

**Recommendation**: Align all Spring Security to **6.1.8** (latest patch)
```groovy
ext {
    springVersion = '6.1.1'
    springSecurityVersion = '6.1.8'
}

dependencies {
    implementation "org.springframework:spring-core:${springVersion}"
    implementation "org.springframework.security:spring-security-core:${springSecurityVersion}"
    implementation "org.springframework.security:spring-security-web:${springSecurityVersion}"
    implementation "org.springframework.security:spring-security-config:${springSecurityVersion}"
    implementation "org.springframework.security:spring-security-taglibs:${springSecurityVersion}"
}
```

### 2.2 Hibernate (CONSISTENT ✓)
- hibernate-core: **6.4.4.Final**
- hibernate-c3p0: **6.4.4.Final**
- hibernate-commons-annotations: **6.0.6.Final** (different release cycle)
- hibernate-validator: **8.0.1.Final** (different project)
- hibernate-search: **6.1.1.Final** (see conflict 1.2)

**Analysis**: Properly aligned

---

## 3. Transitive Dependency Risks

### 3.1 Servlet API / Jakarta EE Migration
**Risk**: Namespace change from `javax.*` to `jakarta.*`

Current state:
- Using jakarta.validation-api:3.0.2
- Hibernate 6.4.4 requires Jakarta EE 9+
- Spring 6.1.1 requires Jakarta EE 9+

**Impact**: Entire stack is on Jakarta EE 9+
**Action Required**:
- Verify all `javax.*` imports changed to `jakarta.*`
- Check servlet container supports Jakarta EE 9+ (Tomcat 10+)

### 3.2 XML Parsing (Multiple Libraries)
**Libraries found**:
- xerces:xercesImpl (2.9.1, 2.10.0)
- xml-apis:1.4.01
- xalan:2.7.0
- dom4j:2.1.3
- woodstox-core-asl:4.0.6

**Risk**: Classpath conflicts, XML parser precedence issues
**Recommendation**:
- Let JDK provide default XML APIs when possible
- Use dom4j for specific DOM requirements
- Minimize explicit XML parser dependencies

---

## 4. Test-Only Dependencies (Low Risk)

These are in `lib/Java/jwebunit` and only affect testing:
- commons-lang3:3.1
- commons-codec:1.7
- commons-io:2.4
- commons-collections:3.2.1
- httpclient:4.5.4
- httpmime:4.2.3
- xercesImpl:2.9.1, 2.10.0

**Gradle Strategy**: Let main dependencies override
```groovy
// Main dependencies take precedence
implementation 'commons-codec:commons-codec:1.15'

// Test dependencies automatically use 1.15
testImplementation 'net.sourceforge.jwebunit:jwebunit-htmlunit-plugin:3.3'
```

---

## 5. Gradle Dependency Resolution Strategy

### 5.1 Force Specific Versions
```groovy
configurations.all {
    resolutionStrategy {
        // Force specific versions to resolve conflicts
        force 'org.apache.commons:commons-lang3:3.12.0'
        force 'org.apache.httpcomponents:httpclient:4.5.10'
        force 'org.apache.httpcomponents:httpmime:4.5.10'
        force 'commons-codec:commons-codec:1.15'

        // Align Spring Security versions
        force 'org.springframework.security:spring-security-core:6.1.8'
        force 'org.springframework.security:spring-security-web:6.1.8'
        force 'org.springframework.security:spring-security-config:6.1.8'
        force 'org.springframework.security:spring-security-taglibs:6.1.8'
        force 'org.springframework.security:spring-security-ldap:6.1.8'
    }
}
```

### 5.2 Exclude Transitive Dependencies (if needed)
```groovy
implementation('some:library:1.0') {
    exclude group: 'commons-collections', module: 'commons-collections'
}
```

### 5.3 Fail on Version Conflict (strict mode)
```groovy
configurations.all {
    resolutionStrategy {
        failOnVersionConflict()
    }
}
```

---

## 6. Migration Recommendations

### High Priority (Phase 2.1)
1. ✅ Align Spring Security to 6.1.8
2. ✅ Use commons-lang3:3.12.0 everywhere
3. ✅ Upgrade httpmime to 4.5.10
4. ✅ Use hibernate-search:6.1.1.Final
5. ✅ Clarify validation-api strategy (Jakarta vs javax)

### Medium Priority (Phase 2.1)
1. Upgrade commons-codec to 1.15
2. Document commons-collections vs commons-collections4 usage
3. Verify Jakarta EE 9+ compatibility

### Low Priority (Phase 2.8)
1. Audit test dependencies for removal
2. Minimize XML parser dependencies
3. Remove exact duplicates (groovycsv)

---

## 7. Testing Strategy (Phase 4)

### 7.1 Version Conflict Validation
```bash
# Check for conflicts
gradle dependencies --configuration runtimeClasspath

# Generate dependency report
gradle dependencyInsight --dependency commons-lang3

# Check for duplicates
gradle dependencies | grep -E "\-\-\-"
```

### 7.2 Runtime Testing
- Verify ClassNotFoundException doesn't occur
- Check for `NoSuchMethodError` (API changes)
- Monitor for XML parser conflicts
- Test validation framework (jakarta vs javax)

---

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Version conflicts cause runtime errors | Medium | High | Comprehensive testing, gradual rollout |
| Jakarta EE migration breaks code | Low | High | Code review for javax.* imports |
| Transitive dependency surprises | Medium | Medium | Use dependency locking |
| XML parser conflicts | Low | Medium | Minimize explicit XML dependencies |
| Test framework breaks | Low | Low | Test early, test often |

---

## Next Steps

1. ✅ Complete Phase 1.5 (this document)
2. Create POC build.gradle with conflict resolutions (Phase 1.6)
3. Test POC WAR generation (Phase 1.7)
4. Implement full build.gradle with all dependencies (Phase 2.1)
5. Validate dependency resolution (Phase 2.9)
6. Runtime testing (Phase 4.7)

---

**Document Version**: 1.0
**Last Updated**: 2025-10-16
**Analyst**: Claude (AI Assistant)
