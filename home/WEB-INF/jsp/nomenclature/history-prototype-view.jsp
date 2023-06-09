<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="/WEB-INF/jsp-include/tag-import.jsp" %>

<jsp:useBean id="marker" type="org.zfin.marker.Marker" scope="request"/>

<z:page>
    <zfin2:dataManager zdbID="${marker.zdbID}"
                       showLastUpdate="true"
                       editURL="javascript:editNomenclature();"/>

    <script>
        window.markerID = '${marker.zdbID}';

        window.reasonList = [
        <c:forEach items="${markerHistoryReasonCodes}" var="reason" varStatus="status">
            '${reason.toString()}',
        </c:forEach>
        ];

        window.markerHistory = [
        <c:forEach var="markerHistory" items="${marker.markerHistory}" varStatus="loop">
            {
                eventName: '${markerHistory.event.toString()}',
                eventDisplay: '${markerHistory.event.display}',
                zdbID: '${markerHistory.zdbID}',
                reason: '${markerHistory.reason.toString()}',
                newValue: '${markerHistory.newValue}',
                oldSymbol: '${markerHistory.oldSymbol}',
                date: '<fmt:formatDate value="${markerHistory.date}" pattern="yyyy-MM-dd"/>',
                comments: '${markerHistory.comments}',
                <c:if test="${!empty markerHistory.attributions }">
                    attributionsSize: '${markerHistory.attributions.size()}',
                    firstPublication: '${markerHistory.attributions.iterator().next().publication.zdbID}',
                </c:if>
            },
        </c:forEach>
        ];
            
        <authz:authorize access="hasRole('root')">
            window.hasRoot = true;
        </authz:authorize>
    </script>

    <div class="__react-root"
         id="NomenclatureEdit"
         data-marker-id="${marker.zdbID}"
    ></div>

    <script src="${zfn:getAssetPath("react.js")}"></script>

</z:page>
