import React, {useState} from 'react';
import PropTypes from 'prop-types';
import LoadingSpinner from '../components/LoadingSpinner';
import AddSequenceModal from '../components/sequence-edit/AddSequenceModal';
import EditSequenceModal from '../components/sequence-edit/EditSequenceModal';
import DeleteSequenceModal from '../components/sequence-edit/DeleteSequenceModal';
import SequenceInformationTable from '../components/sequence-edit/SequenceInformationTable';
import useFetch from '../hooks/useFetch';

const NomenclatureEdit = ({markerId}) => {

    return <>

        <div ng-controller="NomenclatureController as control">
            <zfin2:subsection title="Nomenclature History"
                              test="${!empty marker.markerHistory }"
                              showNoData="true">
                <authz:authorize access="hasRole('root')">
                    <span id="showAllEventsToggle"><a href="javascript:showAll(true)">Show All Events</a></span>
                    <span id="showReducedEventsToggle" style="display: none"><a href="javascript:showAll(false)">Hide Naming
                Events</a></span>
                </authz:authorize>
                <table className="summary sortable">
                    <th id="edit_" style="display: none">Edit</th>
                    <th>New Value</th>
                    <th>Event</th>
                    <th>Old Value</th>
                    <th>Date</th>
                    <th>Reason</th>
                    <th>Comments</th>
                    <c:forEach var="markerHistory" items="${marker.markerHistory}" varStatus="loop">
                        <c:if test="${markerHistory.event.toString() ne 'renamed' }">
                            <tr id="reduced_${loop.index}">
                                <td id="edit_${loop.index}" style="display: none">
                                    <span
                                        ng-click="control.openNomenclatureEditor('${markerHistory.zdbID}','${markerHistory.reason.toString()}', '${loop.index}')"><a
                                        href>Edit</a></span>
                                </td>
                                <td><span className="genedom">${markerHistory.newValue}</span></td>
                                <td>${markerHistory.event.display}</td>
                                <td>
                                    <span className="genedom">${markerHistory.oldSymbol}</span>
                                </td>
                                <td>
                                    <fmt:formatDate value="${markerHistory.date}" pattern="yyyy-MM-dd"/>
                                </td>
                                <td>${markerHistory.reason.toString()}
                                    <c:if test="${!empty markerHistory.attributions }">
                                        <c:choose>
                                            <c:when test="${markerHistory.attributions.size() ==1 }">
                                                (<a
                                                href="/${markerHistory.attributions.iterator().next().publication.zdbID}">1</a>)
                                            </c:when>
                                            <c:otherwise>
                                                (<a
                                                href='/action/publication/list/${markerHistory.zdbID}'>${markerHistory.attributions.size()}</a>)
                                            </c:otherwise>
                                        </c:choose>
                                    </c:if>
                                </td>
                                <td>${markerHistory.comments}</td>
                            </tr>
                        </c:if>
                    </c:forEach>
                    <c:forEach var="markerHistory" items="${marker.markerHistory}" varStatus="loop">
                        <tr style="display: none" id="all_${loop.index}">
                            <td id="edit_${loop.index}" style="display: none">
                                    <span id="data-comments-${loop.index}"
                                          style="display: none">${markerHistory.comments}</span>
                                <span
                                    ng-click="control.openNomenclatureEditor('${markerHistory.zdbID}','${markerHistory.reason.toString()}', '${loop.index}')"><a
                                    href>Edit</a></span>
                            </td>
                            <td><span className="genedom">${markerHistory.newValue}</span></td>
                            <td>${markerHistory.event.display}</td>
                            <td>
                                <span className="genedom">${markerHistory.oldSymbol}</span>
                            </td>
                            <td>
                                <fmt:formatDate value="${markerHistory.date}" pattern="yyyy-MM-dd"/>
                            </td>
                            <td>${markerHistory.reason.toString()}
                                <c:if test="${!empty markerHistory.attributions }">
                                    <c:choose>
                                        <c:when test="${markerHistory.attributions.size() ==1 }">
                                            (<a
                                            href="/${markerHistory.attributions.iterator().next().publication.zdbID}">1</a>)
                                        </c:when>
                                        <c:otherwise>
                                            (<a
                                            href='/action/publication/list/${markerHistory.zdbID}'>${markerHistory.attributions.size()}</a>)
                                        </c:otherwise>
                                    </c:choose>
                                </c:if>
                            </td>
                            <td>${markerHistory.comments}</td>
                        </tr>
                    </c:forEach>
                </table>
            </zfin2:subsection>
    </>;
};

NomenclatureEdit.propTypes = {
    markerId: PropTypes.string,
}

export default NomenclatureEdit;
