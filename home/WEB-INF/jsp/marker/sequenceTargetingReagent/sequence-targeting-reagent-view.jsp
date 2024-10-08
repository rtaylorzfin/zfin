<%@ include file="/WEB-INF/jsp-include/tag-import.jsp" %>

<jsp:useBean id="formBean" class="org.zfin.marker.presentation.SequenceTargetingReagentBean" scope="request"/>
<c:set var="typeName">${formBean.marker.markerType.name}</c:set>

<c:set var="SUMMARY" value="Summary"/>
<c:set var="TARGETLOCATION" value="Target Location"/>
<c:set var="GENOMICFEATURES" value="Genomic Features"/>
<c:set var="CONSTRUCTS" value="Constructs"/>
<c:set var="GENOTYPE" value="Expression"/>
<c:set var="PHENOTYPE" value="Phenotype"/>
<c:set var="CITATIONS" value="Citations"/>


<c:if test="${typeName ne 'MRPHLNO'}">
        <c:set var="sections" value="${[SUMMARY, TARGETLOCATION, CONSTRUCTS, GENOMICFEATURES, GENOTYPE, PHENOTYPE, CITATIONS]}"/>
</c:if>
<c:if test="${typeName eq 'MRPHLNO'}">
        <c:set var="sections" value="${[SUMMARY, TARGETLOCATION, GENOMICFEATURES, GENOTYPE, PHENOTYPE, CITATIONS]}"/>
</c:if>

<z:dataPage sections="${sections}">

    <jsp:attribute name="entityName">
        <zfin:abbrev entity="${formBean.marker}"/>
    </jsp:attribute>

    <jsp:body>
        <z:dataManagerDropdown>
            <a class="dropdown-item" href="/action/marker/str/edit/${formBean.marker.zdbID}">Edit</a>
            <a class="dropdown-item" href="/action/marker/merge?zdbIDToDelete=${formBean.marker.zdbID}">Merge</a>
            <a class="dropdown-item" href="/action/infrastructure/deleteRecord/${formBean.marker.zdbID}">Delete</a>
        </z:dataManagerDropdown>

        <div id="${zfn:makeDomIdentifier(SUMMARY)}">
            <zfin2:markerDataPageHeader marker="${formBean.marker}" />
            <jsp:include page="sequence-targeting-reagent-view-summary.jsp"/>
        </div>

        <z:section title="${TARGETLOCATION}">
            <jsp:include page="sequence-targeting-reagent-view-target-location.jsp"/>
        </z:section>

        <c:if test="${typeName ne 'MRPHLNO'}">
            <z:section title="${CONSTRUCTS}">
                <jsp:include page="sequence-targeting-reagent-view-constructs.jsp"/>
            </z:section>
        </c:if>

        <z:section title="${GENOMICFEATURES}">
            <jsp:include page="sequence-targeting-reagent-view-genonomicfeatures.jsp"/>
        </z:section>

        <z:section title="${GENOTYPE}">
            <z:section title="Gene expression in Wild Types + ${formBean.marker.name}">
                <jsp:include page="sequence-targeting-reagent-view-expression.jsp"/>
            </z:section>
        </z:section>

        <z:section title="${PHENOTYPE}">
            <z:section title="Phenotype resulting from ${formBean.marker.name}">
                <jsp:include page="sequence-targeting-reagent-view-phenotype.jsp" />
            </z:section>
            <z:section title="Phenotype of all Fish created by or utilizing ${formBean.marker.name}">
                <jsp:include page="sequence-targeting-reagent-view-fish-phenotype.jsp" />
            </z:section>
        </z:section>

        <z:section title="${CITATIONS}">
            <div class="__react-root" id="CitationTable" data-marker-id="${formBean.marker.zdbID}"></div>
        </z:section>
    </jsp:body>
</z:dataPage>
