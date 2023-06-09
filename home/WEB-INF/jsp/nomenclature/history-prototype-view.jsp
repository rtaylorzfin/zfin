<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="/WEB-INF/jsp-include/tag-import.jsp" %>

<jsp:useBean id="marker" type="org.zfin.marker.Marker" scope="request"/>

<z:page>
    <zfin2:dataManager zdbID="${marker.zdbID}"
                       showLastUpdate="true"
                       editURL="javascript:editNomenclature();"/>

    <script>
            markerID = '${marker.zdbID}';

            var reasonList = [];
            <c:forEach items="${markerHistoryReasonCodes}" var="reason" varStatus="status">
                reasonList.push('${reason.toString()}');
            </c:forEach>



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