# Product Requirements Document: Gradle-Based WAR Build Process

## Overview

**Goal**: Migrate from manual JAR management to a Gradle-based dependency management system that automatically resolves dependencies from Maven Central and generates a deployable WAR file.

**Current State**:
- JAR files manually managed in `home/WEB-INF/lib/`
- Accessory JARs in `lib/Java/`
- No automated dependency resolution
- Manual deployment from SOURCEROOT to TARGETROOT

**Target State**:
- All dependencies declared in `build.gradle` with Maven Central references
- Automated dependency resolution via Gradle
- Single Gradle command generates deployable WAR file
- Automated deployment process from SOURCEROOT to TARGETROOT
- No JAR files checked into version control (except potentially custom/non-public libraries)

## Stakeholders

- Development Team: Primary implementers and users
- DevOps/Build Team: Deployment automation
- QA Team: Testing new build process

## Success Criteria

1. ✅ All current JAR dependencies identified and catalogued
2. ✅ All dependencies declared in `build.gradle` with Maven Central coordinates
3. ✅ `gradle war` command successfully generates WAR file with all dependencies
4. ✅ WAR file deploys successfully to Tomcat
5. ✅ Application functions identically to current manual JAR management
6. ✅ Automated deployment task from SOURCEROOT to TARGETROOT
7. ✅ CI/CD pipeline updated to use new Gradle build process
8. ✅ No JAR files remain in `home/WEB-INF/lib/` (except documented exceptions)

## Scope

### In Scope

1. **Dependency Inventory & Migration**
   - Audit all JAR files in `home/WEB-INF/lib/`
   - Audit all JAR files in `lib/Java/`
   - Identify Maven Central coordinates for each library
   - Handle version conflicts and compatibility issues

2. **Gradle Configuration**
   - Configure `build.gradle` with all dependencies
   - Set up proper dependency scopes (compile, runtime, test, provided)
   - Configure WAR plugin
   - Configure deployment tasks

3. **Build Process**
   - Create Gradle task to generate WAR file
   - Include all web resources (JSPs, static files, etc.)
   - Include compiled classes
   - Include resolved dependencies in `WEB-INF/lib`

4. **Deployment Automation**
   - Create Gradle task to deploy from SOURCEROOT to TARGETROOT
   - Handle Tomcat deployment (stop/start/restart)
   - Backup previous deployment

5. **Testing & Validation**
   - Verify all dependencies resolve correctly
   - Verify WAR structure matches current deployment
   - Functional testing of deployed application
   - Performance comparison

6. **Documentation & CI/CD Updates**
   - Update build documentation
   - Update CI/CD pipeline configuration
   - Update deployment runbooks

### Out of Scope

- Refactoring application code or architecture
- Upgrading library versions (unless required for migration)
- Changing application server (staying with Tomcat)
- Migration to containerized deployment (future consideration)

## Technical Requirements

### 1. Dependency Inventory

**Current JAR Locations:**
- `home/WEB-INF/lib/` - Primary application dependencies
- `lib/Java/` - Accessory/utility libraries

**Deliverables:**
- Spreadsheet/document listing:
  - JAR filename
  - Version (from filename or manifest)
  - Maven Central coordinates (groupId:artifactId:version)
  - Purpose/usage notes
  - Dependencies (if known)
  - Conflicts (if any)
  - Status (found in Maven Central / custom library / need alternative)

**Example:**
```
hibernate-core-5.4.32.Final.jar
→ org.hibernate:hibernate-core:5.4.32.Final
→ Purpose: ORM framework
→ Status: Available in Maven Central
```

### 2. build.gradle Configuration

**Required Plugins:**
```groovy
plugins {
    id 'war'
    id 'java'
}
```

**Dependency Configuration:**
```groovy
repositories {
    mavenCentral()
    // Add any private repositories if needed
}

dependencies {
    // Example structure
    implementation 'org.hibernate:hibernate-core:5.4.32.Final'
    providedCompile 'javax.servlet:javax.servlet-api:4.0.1'
    runtimeOnly 'org.postgresql:postgresql:42.2.20'
    testImplementation 'junit:junit:4.13.2'

    // All dependencies from WEB-INF/lib and lib/Java
    // ...
}

// WAR configuration
war {
    archiveFileName = 'zfin.war'
    webAppDirectory = file('home')

    // Include specific resources
    from('src/main/webapp') {
        include '**/*'
    }
}
```

**Dependency Scopes:**
- `implementation`: Compile and runtime dependencies
- `providedCompile`: Provided by container (e.g., servlet-api)
- `runtimeOnly`: Only needed at runtime
- `testImplementation`: Test dependencies
- `compileOnly`: Compile-time only dependencies

### 3. WAR Structure

**Generated WAR should contain:**
```
zfin.war
├── META-INF/
│   └── MANIFEST.MF
├── WEB-INF/
│   ├── web.xml
│   ├── classes/
│   │   └── (compiled .class files)
│   └── lib/
│       └── (all resolved dependencies)
└── (web resources: JSPs, HTML, CSS, JS, images)
```

**Validation:**
- Compare generated WAR structure with current deployment
- Verify all expected JARs are present
- Verify no duplicate or conflicting JARs
- Check file sizes and timestamps

### 4. Deployment Tasks

**Gradle Deployment Task:**
```groovy
task deploy(type: Copy, dependsOn: war) {
    description = 'Deploy WAR file from SOURCEROOT to TARGETROOT'

    from war.archiveFile
    into "${System.getenv('TARGETROOT')}/catalina_bases/zfin.org/webapps"
    rename { 'zfin.war' }

    doFirst {
        // Stop Tomcat
        exec {
            commandLine 'docker', 'compose', 'down', 'tomcat'
            workingDir 'docker/'
        }

        // Backup current deployment
        exec {
            commandLine 'sh', '-c',
                "cp -r ${System.getenv('TARGETROOT')}/catalina_bases/zfin.org/webapps/zfin " +
                "${System.getenv('TARGETROOT')}/catalina_bases/zfin.org/webapps/zfin.backup.\$(date +%Y%m%d_%H%M%S)"
        }
    }

    doLast {
        // Start Tomcat
        exec {
            commandLine 'docker', 'compose', 'up', '-d', 'tomcat'
            workingDir 'docker/'
        }
    }
}
```

### 5. Custom/Non-Public Libraries

**Strategy for libraries not in Maven Central:**

1. **Option A: Local Maven Repository**
   - Install custom JARs to local Maven repo
   - Reference as normal dependencies

2. **Option B: File Dependencies**
   ```groovy
   dependencies {
       implementation files('lib/custom/proprietary-lib.jar')
   }
   ```

3. **Option C: Private Maven Repository**
   - Set up Artifactory/Nexus
   - Publish custom libraries
   - Add repository to build.gradle

**Decision:** Document which approach to use for each custom library

## Implementation Plan

### Phase 1: Discovery & Planning (1-2 weeks)

**Tasks:**
1. **Inventory all JAR files**
   - Run: `find home/WEB-INF/lib lib/Java -name "*.jar" -type f`
   - Create dependency inventory spreadsheet

2. **Identify Maven coordinates**
   - For each JAR, search Maven Central
   - Document version numbers
   - Note any libraries not available in Maven Central

3. **Analyze dependency tree**
   - Identify transitive dependencies
   - Find potential conflicts
   - Plan resolution strategy

4. **Create proof of concept**
   - Create minimal build.gradle with subset of dependencies
   - Generate test WAR file
   - Validate structure

**Deliverables:**
- Complete dependency inventory
- Draft build.gradle
- POC WAR file
- Risk assessment document

### Phase 2: Build Configuration (2-3 weeks)

**Tasks:**
1. **Complete build.gradle configuration**
   - Add all dependencies with correct scopes
   - Configure WAR plugin
   - Set up proper source sets
   - Configure resource copying

2. **Handle custom libraries**
   - Decide on strategy for each custom library
   - Implement chosen approach
   - Test resolution

3. **Create build tasks**
   - `gradle clean` - Clean build artifacts
   - `gradle build` - Compile and test
   - `gradle war` - Generate WAR file
   - `gradle deploy` - Deploy to TARGETROOT

4. **Test build process**
   - Generate WAR file
   - Compare with current deployment
   - Validate all dependencies present
   - Check for duplicates or conflicts

**Deliverables:**
- Complete build.gradle
- Tested WAR generation process
- Build documentation

### Phase 3: Deployment Automation (1-2 weeks)

**Tasks:**
1. **Create deployment tasks**
   - Implement deploy task
   - Add backup mechanism
   - Add rollback capability

2. **Test deployment process**
   - Deploy to dev environment
   - Deploy to test environment
   - Validate application functionality

3. **Update existing Ant tasks**
   - Migrate relevant Ant tasks to Gradle
   - Update task dependencies
   - Preserve existing functionality

**Deliverables:**
- Working deployment automation
- Deployment runbook
- Rollback procedure

### Phase 4: Testing & Validation (2-3 weeks)

**Tasks:**
1. **Functional testing**
   - Deploy generated WAR to test environment
   - Run full regression test suite
   - Compare behavior with current deployment

2. **Performance testing**
   - Compare startup times
   - Compare runtime performance
   - Monitor resource usage

3. **Integration testing**
   - Test with CI/CD pipeline
   - Test with Docker builds
   - Test deployment process

4. **Dependency validation**
   - Verify all libraries load correctly
   - Check for ClassNotFoundException
   - Check for version conflicts
   - Validate transitive dependencies

**Deliverables:**
- Test results
- Performance comparison
- Issue log and resolutions

### Phase 5: CI/CD Integration (1 week)

**Tasks:**
1. **Update CI/CD pipeline**
   - Modify Cell.gopipeline.json
   - Update build stages to use Gradle
   - Update deployment stages

2. **Update documentation**
   - Build process documentation
   - Deployment documentation
   - Troubleshooting guide

3. **Training**
   - Train team on new build process
   - Document common issues
   - Create FAQ

**Deliverables:**
- Updated CI/CD configuration
- Complete documentation
- Training materials

### Phase 6: Migration & Cleanup (1 week)

**Tasks:**
1. **Final migration**
   - Switch production builds to Gradle
   - Monitor for issues
   - Quick rollback if needed

2. **Cleanup**
   - Remove JAR files from WEB-INF/lib (with backup)
   - Remove JAR files from lib/Java (with backup)
   - Update .gitignore
   - Remove old build scripts (after verification period)

3. **Documentation**
   - Update project README
   - Document exceptions (if any JARs remain)
   - Create migration postmortem

**Deliverables:**
- Clean repository
- Final documentation
- Migration report

## Risk Assessment

### High Risk

1. **Dependency Version Conflicts**
   - Risk: Gradle may resolve different versions than manually managed JARs
   - Mitigation: Use dependency locking, test thoroughly, document all version decisions

2. **Missing Dependencies**
   - Risk: Some JARs may not be in Maven Central
   - Mitigation: Early identification, plan for custom libraries, consider alternatives

3. **Runtime Failures**
   - Risk: Application fails after migration due to missing or wrong dependencies
   - Mitigation: Comprehensive testing, staged rollout, easy rollback plan

### Medium Risk

4. **Build Performance**
   - Risk: Gradle builds may be slower than current process
   - Mitigation: Use Gradle daemon, build cache, parallel builds

5. **CI/CD Disruption**
   - Risk: New build process breaks existing pipelines
   - Mitigation: Test in separate pipeline, parallel runs during transition

### Low Risk

6. **Learning Curve**
   - Risk: Team unfamiliar with Gradle
   - Mitigation: Training, documentation, gradual adoption

## Dependencies & Prerequisites

**Required:**
- Gradle 7.6.5 (already installed)
- Maven Central access
- Docker Compose (for deployment)
- Test environment for validation

**Optional:**
- Private Maven repository (if needed for custom libraries)
- Dependency analysis tools (e.g., Gradle dependency-insight)

## Success Metrics

1. **Build Success Rate**: 100% of builds produce valid WAR file
2. **Deployment Success Rate**: 100% of deployments complete successfully
3. **Test Pass Rate**: 100% of existing tests pass with new build
4. **Performance**: Build time < 5 minutes, deployment time < 2 minutes
5. **Zero Manual Intervention**: No manual JAR management required
6. **Zero Production Issues**: No runtime issues related to dependency changes

## Open Questions

1. Are there any proprietary/licensed JARs that cannot be distributed via Maven?
2. What is the current version of each dependency? (needs inventory)
3. Are there any version constraints or compatibility requirements?
4. Should we upgrade any dependencies as part of this migration?
5. What is the rollback procedure if the migration fails in production?
6. Are there any dependencies on specific JAR file names or locations?
7. How do we handle snapshot/beta versions if any exist?

## Appendices

### A. Current Directory Structure
```
SOURCEROOT/
├── home/WEB-INF/
│   ├── lib/           ← Manual JAR management (to be removed)
│   ├── classes/
│   └── web.xml
├── lib/Java/          ← Accessory JARs (to be removed)
└── build.gradle       ← New dependency management
```

### B. Target Directory Structure
```
SOURCEROOT/
├── home/WEB-INF/
│   ├── classes/
│   └── web.xml
├── build.gradle       ← All dependencies declared here
├── build/
│   └── libs/
│       └── zfin.war   ← Generated WAR with dependencies
└── lib/custom/        ← Only custom/non-public JARs (if any)
```

### C. Example Dependency Declaration

**Before (Manual):**
```
home/WEB-INF/lib/hibernate-core-5.4.32.Final.jar
home/WEB-INF/lib/postgresql-42.2.20.jar
home/WEB-INF/lib/spring-core-5.3.10.jar
```

**After (Gradle):**
```groovy
dependencies {
    implementation 'org.hibernate:hibernate-core:5.4.32.Final'
    implementation 'org.postgresql:postgresql:42.2.20'
    implementation 'org.springframework:spring-core:5.3.10'
}
```

### D. Useful Gradle Commands

```bash
# Clean build
gradle clean

# Compile and run tests
gradle build

# Generate WAR file
gradle war

# Show dependency tree
gradle dependencies

# Show dependency insight for specific library
gradle dependencyInsight --dependency hibernate-core

# Deploy to TARGETROOT
gradle deploy

# Deploy without tests
gradle deploy -x test

# Show build tasks
gradle tasks
```

### E. Migration Checklist

- [ ] Complete JAR inventory
- [ ] Identify all Maven coordinates
- [ ] Handle custom libraries
- [ ] Create build.gradle
- [ ] Test WAR generation
- [ ] Test deployment process
- [ ] Run full test suite
- [ ] Update CI/CD pipeline
- [ ] Train team
- [ ] Migrate production
- [ ] Remove old JAR files
- [ ] Update documentation
- [ ] Monitor production

---

**Document Version**: 1.0
**Last Updated**: 2025-10-16
**Author**: Development Team
**Status**: Draft
