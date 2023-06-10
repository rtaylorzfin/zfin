<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="/WEB-INF/jsp-include/tag-import.jsp" %>

<jsp:useBean id="marker" type="org.zfin.marker.Marker" scope="request"/>

<z:page>
    <script>
        window.markerID = '${marker.zdbID}';

        window.reasonList = [
        <c:forEach items="${markerHistoryReasonCodes}" var="reason" varStatus="status">
            '${reason.toString()}',
        </c:forEach>
        ];

        <authz:authorize access="hasRole('root')">
            window.hasRoot = true;
        </authz:authorize>

        debuggingInfo = {
            markerHistory: ${markerHistoryJsonRaw}
        };
    </script>

    <div class="__react-root"
         id="NomenclatureEdit"
         data-marker-id="${marker.zdbID}"
         data-marker-history-json="${markerHistoryJson}"
         data-has-root="<authz:authorize access="hasRole('root')">true</authz:authorize>"
    ></div>

    <script src="${zfn:getAssetPath("react.js")}"></script>

</z:page>
