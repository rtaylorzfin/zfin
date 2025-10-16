# Gradle WAR Build Migration - Risk Assessment

## Executive Summary

**Project**: Migration from manual JAR management to Gradle-based dependency management with automated WAR generation

**Assessment Date**: 2025-10-16

**Overall Risk Level**: **MEDIUM** ⚠️

**Recommendation**: Proceed with migration using phased rollout approach with comprehensive testing at each stage.

---

## Risk Matrix

| Risk Category | Likelihood | Impact | Overall Risk | Priority |
|---------------|------------|--------|--------------|----------|
| Dependency Version Conflicts | High | High | **CRITICAL** 🔴 | P0 |
| Jakarta EE Migration Issues | Medium | High | **HIGH** 🟠 | P1 |
| Runtime ClassNotFoundException | Medium | High | **HIGH** 🟠 | P1 |
| Build Performance Degradation | Low | Medium | **MEDIUM** 🟡 | P2 |
| Custom JAR Incompatibilities | Medium | Medium | **MEDIUM** 🟡 | P2 |
| CI/CD Pipeline Disruption | Low | High | **MEDIUM** 🟡 | P2 |
| Transitive Dependency Surprises | Medium | Medium | **MEDIUM** 🟡 | P2 |
| Team Learning Curve | Low | Medium | **LOW** 🟢 | P3 |
| Rollback Complexity | Low | Low | **LOW** 🟢 | P3 |

---

## Critical Risks (P0)

### RISK-001: Dependency Version Conflicts
**Category**: Technical - Dependencies
**Likelihood**: High (80%)
**Impact**: High - Application failure, runtime errors
**Overall Risk**: 🔴 **CRITICAL**

#### Description
Gradle's automatic dependency resolution may select different versions than the manually managed JARs, leading to:
- Breaking API changes
- `NoSuchMethodError` at runtime
- Incompatible behavior between libraries
- Transitive dependency version mismatches

#### Evidence from Analysis
- **12 version conflicts identified** in Phase 1.5
- Spring Security version mismatch (6.1.1 → 6.1.8)
- commons-lang3 major version gap (3.1 → 3.12.0)
- httpmime misalignment with httpclient (4.4.1 vs 4.5.10)

#### Potential Consequences
- Application fails to start
- Features break at runtime
- Data corruption if ORM behaves differently
- Security vulnerabilities if security libraries incompatible

#### Mitigation Strategies

**Preventive (Before Deployment)**:
1. ✅ **Force specific versions** using `resolutionStrategy.force()`
   ```groovy
   configurations.all {
       resolutionStrategy {
           force 'org.apache.commons:commons-lang3:3.12.0'
           force "org.springframework.security:spring-security-core:${springSecurityVersion}"
       }
   }
   ```

2. ✅ **Use dependency locking** for reproducible builds
   ```bash
   gradle dependencies --write-locks
   ```

3. **Generate dependency comparison report**
   ```bash
   # Current vs Gradle-resolved
   diff <(ls home/WEB-INF/lib/) <(gradle listWarJars)
   ```

4. **Run dependency insight for critical libraries**
   ```bash
   gradle dependencyInsight --dependency hibernate-core
   gradle dependencyInsight --dependency spring-security-core
   ```

**Detective (During Testing)**:
1. **Comprehensive integration testing** in Phase 4.1-4.3
2. **Monitor startup logs** for version warnings
3. **Run full regression test suite** (Phase 4.2)
4. **Check for `NoSuchMethodError` or `ClassNotFoundException`**

**Corrective (If Issues Found)**:
1. Use `exclude` to prevent unwanted transitive dependencies
2. Add explicit version constraints in build.gradle
3. Create dependency substitution rules if needed
4. Rollback to manual JAR management if critical failures

#### Contingency Plan
- Keep backup of current `home/WEB-INF/lib/` (Phase 6.3)
- Document exact versions of all working libraries
- Maintain ability to quickly revert build.gradle
- Have rollback deployment ready (Phase 3.4)

#### Success Criteria
- ✅ All version conflicts documented and resolved
- ✅ Dependency resolution strategy in place
- 🔲 Full regression tests pass
- 🔲 No runtime `NoSuchMethodError` or version warnings

**Status**: Partially Mitigated (POC successful, full testing pending)

---

## High Risks (P1)

### RISK-002: Jakarta EE Migration Issues
**Category**: Technical - Platform
**Likelihood**: Medium (50%)
**Impact**: High - Application incompatibility
**Overall Risk**: 🟠 **HIGH**

#### Description
Migration from `javax.*` to `jakarta.*` namespace (Java EE → Jakarta EE) may cause:
- Import statement failures
- Annotation processing errors
- Reflection-based code failures
- Third-party library incompatibilities

#### Evidence
- Using `jakarta.validation-api:3.0.2` (new namespace)
- Hibernate 6.4.4 requires Jakarta EE 9+
- Spring 6.1.1 requires Jakarta EE 9+
- Legacy `javax.validation-api:1.0.0.GA` still present (for GWT?)

#### Potential Consequences
- Compilation errors if javax imports not updated
- Runtime errors with mixed javax/jakarta classes
- Servlet container compatibility issues (requires Tomcat 10+)
- Third-party tools may not support Jakarta EE

#### Mitigation Strategies

**Preventive**:
1. **Verify Tomcat version** supports Jakarta EE 9+ (Tomcat 10.0+)
   - Check docker/tomcat/Dockerfile for version
   - Current: Need to verify

2. **Audit codebase for javax.* imports**
   ```bash
   grep -r "import javax\." src/ | grep -v "javax.sql\|javax.crypto"
   ```

3. **Update imports to jakarta.***
   - Focus on: validation, servlet, persistence, xml.bind
   - Use IDE refactoring tools

4. **Check GWT compatibility** with Jakarta EE
   - GWT may require javax.validation (legacy)
   - May need to keep both namespaces

**Detective**:
1. **Compile application** and check for import errors
2. **Run tests** with Jakarta EE libraries
3. **Test servlet functionality** in Tomcat
4. **Verify reflection-based frameworks** (Spring, Hibernate) work

**Corrective**:
1. Use compatibility layers if needed
2. Keep javax versions for specific use cases (GWT)
3. Upgrade third-party libraries to Jakarta-compatible versions

#### Contingency Plan
- Maintain both javax and jakarta dependencies if required
- Document which code uses which namespace
- Plan gradual migration if full switch fails

#### Success Criteria
- 🔲 Tomcat version confirmed Jakarta EE 9+ compatible
- 🔲 All javax.* imports updated to jakarta.*
- 🔲 GWT compatibility verified
- 🔲 Application deploys and runs in Tomcat

**Status**: Not Started (Requires investigation)

---

### RISK-003: Runtime ClassNotFoundException
**Category**: Technical - Dependencies
**Likelihood**: Medium (50%)
**Impact**: High - Application failure
**Overall Risk**: 🟠 **HIGH**

#### Description
Missing or incorrectly packaged dependencies may cause:
- `ClassNotFoundException` at runtime
- `NoClassDefFoundError` for transitive dependencies
- Missing service provider configurations
- Resource files not packaged

#### Evidence
- POC WAR includes 259 JARs vs 169 manually managed
- 90 additional JARs from transitive dependencies
- Some custom JARs have unknown dependencies
- Configuration files may reference missing classes

#### Potential Consequences
- Application fails to start
- Features fail when triggered at runtime
- Silent failures if error handling swallows exceptions
- Hard-to-debug "works in dev, fails in prod" scenarios

#### Mitigation Strategies

**Preventive**:
1. **Verify all custom JARs have dependencies included**
   - Check manifest.mf for Class-Path entries
   - Test each custom JAR individually

2. **Compare WAR contents** with current deployment
   ```bash
   unzip -l current_deployment.war | grep "WEB-INF/lib" > current_jars.txt
   unzip -l build/libs/zfin.war | grep "WEB-INF/lib" > gradle_jars.txt
   diff current_jars.txt gradle_jars.txt
   ```

3. **Include all resource files**
   - Spring configuration files
   - Hibernate mapping files
   - Log4j configuration
   - Service provider files (META-INF/services/)

**Detective**:
1. **Test all application features** systematically (Phase 4.2)
2. **Monitor startup logs** for initialization errors
3. **Check for lazy-loading errors** during runtime testing
4. **Verify database connectivity** and ORM functionality

**Corrective**:
1. Add missing dependencies to build.gradle
2. Check for typos in dependency coordinates
3. Verify providedCompile vs implementation scopes
4. Ensure Tomcat provides expected libraries

#### Contingency Plan
- Keep detailed logs of all ClassNotFoundExceptions
- Have mechanism to add JARs on-the-fly for testing
- Document workarounds for known missing classes

#### Success Criteria
- 🔲 Application starts without ClassNotFoundException
- 🔲 All features tested successfully
- 🔲 No lazy-loading failures during runtime
- 🔲 WAR contents match expected structure

**Status**: Not Started (Requires testing)

---

## Medium Risks (P2)

### RISK-004: Build Performance Degradation
**Category**: Operational - Build Process
**Likelihood**: Low (30%)
**Impact**: Medium - Developer productivity
**Overall Risk**: 🟡 **MEDIUM**

#### Description
Gradle builds may be slower than current process, causing:
- Longer CI/CD pipeline times
- Developer frustration during local builds
- Resource consumption on build servers

#### Evidence
- POC build: ~20 seconds (reasonable)
- Dependency resolution requires Maven Central access
- First build downloads ~250 dependencies
- Subsequent builds should use cache

#### Potential Consequences
- Slower development iteration cycle
- CI/CD pipeline timeouts
- Increased build server costs
- Developer dissatisfaction

#### Mitigation Strategies

**Preventive**:
1. **Enable Gradle daemon** (default in modern Gradle)
2. **Use build cache**
   ```groovy
   buildCache {
       local {
           enabled = true
       }
   }
   ```

3. **Configure parallel execution**
   ```bash
   gradle war --parallel
   ```

4. **Use shared dependency cache** in CI/CD
5. **Optimize dependency resolution**
   - Use version ranges carefully
   - Minimize dynamic versions

**Detective**:
1. **Measure build times** before and after migration
2. **Profile builds** using `--profile` flag
3. **Monitor CI/CD pipeline** execution times

**Corrective**:
1. Tune Gradle memory settings
2. Use Gradle Enterprise for build insights
3. Optimize task dependencies
4. Consider local Maven mirror

#### Success Criteria
- 🔲 Local builds complete in < 30 seconds (after first build)
- 🔲 CI/CD pipeline time not increased > 20%
- 🔲 Build cache hit rate > 80%

**Status**: POC Successful (20s build time acceptable)

---

### RISK-005: Custom JAR Incompatibilities
**Category**: Technical - Dependencies
**Likelihood**: Medium (40%)
**Impact**: Medium - Feature degradation
**Overall Risk**: 🟡 **MEDIUM**

#### Description
The 19 custom/non-public JARs may have issues:
- Incompatible with new framework versions
- Missing transitive dependencies
- Undocumented version requirements
- Internal APIs changed in dependencies

#### Evidence
- 19 custom JARs identified in Phase 1.4
- 4 confirmed: agr_curation_api, bbop, obo, robot
- 3 Eclipse-transformed (may need originals)
- 12 unknown/investigation needed

#### Potential Consequences
- AGR integration fails
- Ontology processing breaks
- Custom tools unavailable
- Data processing failures

#### Mitigation Strategies

**Preventive**:
1. **Test each custom JAR independently** (Phase 2.4)
2. **Document each custom JAR's purpose and usage**
3. **Check for Maven Central alternatives**
4. **Verify Eclipse transformations still needed**

**Detective**:
1. **Test features using custom JARs** specifically
2. **Monitor for errors** related to custom code
3. **Validate AGR API integration**
4. **Test ontology processing pipelines**

**Corrective**:
1. Update custom JARs to compatible versions
2. Find Maven Central replacements where possible
3. Fork and maintain custom JARs if needed
4. Document workarounds for incompatibilities

#### Success Criteria
- 🔲 All custom JARs tested and documented
- 🔲 AGR integration functional
- 🔲 Ontology tools working
- 🔲 No custom JAR-related runtime errors

**Status**: Identified (Documentation in Phase 1.4)

---

### RISK-006: CI/CD Pipeline Disruption
**Category**: Operational - Deployment
**Likelihood**: Low (30%)
**Impact**: High - Deployment blocked
**Overall Risk**: 🟡 **MEDIUM**

#### Description
Changes to build process may break CI/CD pipeline:
- GoCD pipeline configuration incompatible
- Build artifacts in wrong location
- Deployment scripts expect different structure
- Environment variables missing

#### Evidence
- Current pipeline uses Ant/manual process
- Pipeline configuration in Cell.gopipeline.json
- Docker-based builds in compile container
- Integration with Jenkins

#### Potential Consequences
- Cannot deploy to any environment
- Manual deployments required
- Release process blocked
- Emergency hotfixes delayed

#### Mitigation Strategies

**Preventive**:
1. **Update Cell.gopipeline.json** (Phase 5.1-5.2)
2. **Test in parallel pipeline** first
3. **Keep old pipeline** as backup during transition
4. **Document all pipeline changes**

**Detective**:
1. **Test pipeline in non-production** environment first
2. **Validate artifact locations** and formats
3. **Test deployment scripts** with new WAR
4. **Verify environment variable** propagation

**Corrective**:
1. Quickly revert pipeline to previous version
2. Use manual deployment temporarily
3. Fix issues incrementally
4. Maintain dual pipeline during transition

#### Contingency Plan
- Keep old build process available for 2-4 weeks
- Document manual deployment procedure
- Have dedicated person for pipeline support

#### Success Criteria
- 🔲 Pipeline builds WAR successfully
- 🔲 Deployments work in all environments
- 🔲 No manual intervention needed
- 🔲 Build artifacts in correct locations

**Status**: Not Started (Phase 5)

---

### RISK-007: Transitive Dependency Surprises
**Category**: Technical - Dependencies
**Likelihood**: Medium (50%)
**Impact**: Medium - Unexpected behavior
**Overall Risk**: 🟡 **MEDIUM**

#### Description
Gradle includes 90+ additional transitive dependencies that may:
- Conflict with explicitly managed versions
- Introduce unexpected behavior changes
- Add security vulnerabilities
- Increase WAR size significantly

#### Evidence
- POC WAR: 259 JARs (90 more than manual)
- Transitive dependencies not previously managed
- Version conflicts in transitive tree possible
- Some may override expected libraries

#### Potential Consequences
- Subtle bugs from unexpected library versions
- Performance degradation from bloated dependencies
- Security audit failures from vulnerable transitive deps
- Increased deployment size and memory usage

#### Mitigation Strategies

**Preventive**:
1. **Review full dependency tree**
   ```bash
   gradle dependencies > dependencies.txt
   ```

2. **Use dependency insight** for critical libraries
3. **Exclude unwanted transitive dependencies**
   ```groovy
   implementation('some:library:1.0') {
       exclude group: 'unwanted', module: 'dependency'
   }
   ```

4. **Run security scan** on all dependencies
   ```bash
   gradle dependencyCheckAnalyze
   ```

**Detective**:
1. **Monitor application behavior** for unexpected changes
2. **Check memory usage** and WAR size
3. **Run performance tests** (Phase 4.5-4.6)
4. **Security vulnerability scan** (OWASP Dependency Check)

**Corrective**:
1. Exclude problematic transitive dependencies
2. Force specific versions that work
3. Find alternative primary dependencies with better transitive tree
4. Consider shading if necessary

#### Success Criteria
- 🔲 All transitive dependencies reviewed
- 🔲 No high-severity security vulnerabilities
- 🔲 WAR size acceptable (< 600 MB)
- 🔲 Memory usage within expected range

**Status**: Partially Known (POC shows 259 JARs)

---

## Low Risks (P3)

### RISK-008: Team Learning Curve
**Category**: Organizational - Skills
**Likelihood**: Low (20%)
**Impact**: Medium - Temporary slowdown
**Overall Risk**: 🟢 **LOW**

#### Description
Team unfamiliar with Gradle may experience:
- Slower initial development
- Questions about build configuration
- Mistakes in dependency management
- Resistance to change

#### Mitigation Strategies
1. **Training sessions** (Phase 5.7)
2. **Comprehensive documentation** (Phase 5.4-5.6)
3. **Gradle command cheat sheet**
4. **Dedicated support** person during transition

#### Success Criteria
- 🔲 Team training completed
- 🔲 Documentation available
- 🔲 FAQ created
- 🔲 Team comfortable with Gradle commands

---

### RISK-009: Rollback Complexity
**Category**: Operational - Deployment
**Likelihood**: Low (20%)
**Impact**: Low - Temporary disruption
**Overall Risk**: 🟢 **LOW**

#### Description
Rolling back to manual JAR management may be complex if issues found late.

#### Mitigation Strategies
1. **Maintain JAR backups** (Phase 6.3-6.4)
2. **Document rollback procedure** (Phase 3.4)
3. **Keep old build scripts** for 4 weeks (Phase 6.6)
4. **Test rollback process** before production

#### Success Criteria
- 🔲 Rollback procedure documented
- 🔲 JAR backups maintained
- 🔲 Rollback tested successfully

---

## Risk Mitigation Timeline

### Phase 2: Build Configuration (2-3 weeks)
- **RISK-001**: Implement version forcing strategy
- **RISK-005**: Investigate all custom JARs
- **RISK-007**: Review transitive dependencies

### Phase 3: Deployment Automation (1-2 weeks)
- **RISK-009**: Create rollback procedure
- **RISK-006**: Update deployment scripts

### Phase 4: Testing & Validation (2-3 weeks)
- **RISK-001**: Validate version conflict resolutions
- **RISK-002**: Test Jakarta EE compatibility
- **RISK-003**: Comprehensive runtime testing
- **RISK-004**: Measure build performance
- **RISK-005**: Test custom JAR functionality
- **RISK-007**: Monitor for transitive dependency issues

### Phase 5: CI/CD Integration (1 week)
- **RISK-006**: Update and test pipeline
- **RISK-008**: Team training

### Phase 6: Migration & Cleanup (1 week)
- **RISK-001**: Final production validation
- **RISK-009**: Execute with rollback ready

---

## Risk Acceptance Criteria

### Go/No-Go Decision Points

**After Phase 2 (Build Configuration Complete)**:
- ✅ All 247 dependencies declared in build.gradle
- ✅ Version conflicts documented and resolved
- ✅ Custom JARs strategy determined
- ✅ WAR generates successfully

**After Phase 4 (Testing Complete)**:
- ✅ Full regression tests pass (100%)
- ✅ No ClassNotFoundException or NoSuchMethodError
- ✅ Performance within 10% of current
- ✅ Security scan passes

**Before Phase 6 (Production Migration)**:
- ✅ CI/CD pipeline working
- ✅ Team trained
- ✅ Rollback procedure tested
- ✅ Stakeholder approval

---

## Overall Risk Conclusion

### Risk Summary
- **1 Critical Risk**: Dependency version conflicts (mitigatable)
- **2 High Risks**: Jakarta EE migration, ClassNotFoundException (mitigatable with testing)
- **4 Medium Risks**: All manageable with proper planning
- **2 Low Risks**: Minimal concern

### Recommendation
**PROCEED** with migration using the following approach:

1. **Phased Rollout**:
   - Phase 2-3: Build and deployment automation
   - Phase 4: Extensive testing (2-3 weeks minimum)
   - Phase 5: CI/CD integration
   - Phase 6: Production migration with monitoring

2. **Risk Management**:
   - Comprehensive testing at each phase
   - Maintain rollback capability
   - Monitor production closely post-migration
   - Keep old process available for 4 weeks

3. **Success Factors**:
   - POC successful (Phase 1.7) ✅
   - Clear documentation and planning ✅
   - Team buy-in and training
   - Adequate testing time

### Alternative Approaches (If Risks Too High)
1. **Incremental Migration**: Migrate subset of dependencies first
2. **Parallel Systems**: Run both old and new builds simultaneously
3. **Defer Migration**: Wait for framework updates that simplify migration

---

**Assessment Version**: 1.0
**Date**: 2025-10-16
**Next Review**: After Phase 2 completion
**Approved By**: [Pending]
