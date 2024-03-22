import React, {useEffect} from 'react';

interface ConstructRelationshipsTableProps {
    publicationId: string;
}

//type for rows of table:
type ConstructRelationshipRow = {
    zdbID: string;
    constructZdbID: string;
    constructType: string;
    constructLabel: string;
    relationshipType: string;
    markerLabel: string;
    markerZdbID: string;
}

type MarkerNameAndZdbId = {
    label: string;
    zdbID: string;
}


//TODO: This is a hack to get the domain for developing locally.  It should be removed when this is deployed to production.
let calculatedDomain = window.location.origin;
if (calculatedDomain.indexOf('localhost') > -1) {
    calculatedDomain = 'https://cell-mac.zfin.org';
}

console.log('test');

const ConstructRelationshipsTable = ({publicationId}: ConstructRelationshipsTableProps) => {
    const [loading, setLoading] = React.useState<boolean>(true);
    const [constructRelationshipRows, setConstructRelationshipRows] = React.useState<ConstructRelationshipRow[]>([]);
    const [publicationConstructs, setPublicationConstructs] = React.useState<MarkerNameAndZdbId[]>([]);
    const [markersForRelation, setMarkersForRelation] = React.useState<MarkerNameAndZdbId[]>([]);
    const RELATIONSHIP_TO_ADD = 'contains region';

    useEffect(() => {
        async function fetchConstructRelationships() {
            setLoading(true); // Assuming you want to set loading to true at the beginning of the fetch
            try {
                const response = await fetch(`${calculatedDomain}/action/api/publication/${publicationId}/constructs`);
                const constructsData = await response.json();
                let uniqueConstructsMap = {};

                let uniqueConstructs = [];

                let lastConstructZdbId = '';
                const mappedConstructRelationships = constructsData.map(({ zdbID, constructDTO, markerDTO, relationshipType }) => {
                    const { zdbID: constructZdbID, constructType: constructType, name: constructName } = constructDTO;
                    const { label: markerLabel, zdbID: markerZdbID } = markerDTO;

                    if (uniqueConstructsMap[constructZdbID] === undefined) {
                        uniqueConstructs.push({label: constructName, zdbID: constructZdbID});
                    }
                    uniqueConstructsMap[constructZdbID] = constructName;

                    const constructLabel = lastConstructZdbId === constructZdbID ? '' : constructName;
                    lastConstructZdbId = constructZdbID;

                    return {
                        zdbID,
                        constructZdbID,
                        constructType,
                        constructLabel,
                        relationshipType,
                        markerLabel,
                        markerZdbID,
                    };
                });

                setPublicationConstructs(uniqueConstructs);

                setConstructRelationshipRows(mappedConstructRelationships);
            } catch (error) {
                console.error('Failed to fetch construct relationships:', error);
            } finally {
                setLoading(false);
            }
        }

        fetchConstructRelationships();
    }, [publicationId]);

    useEffect(() => {
        async function fetchMarkersForRelation() {
            try {
                const response = await fetch(`${calculatedDomain}/action/api/publication/${publicationId}/${RELATIONSHIP_TO_ADD}/markersForRelation`);
                const markersData = await response.json();

                setMarkersForRelation(markersData.map(({ label, zdbID }) => ({ label, zdbID })).sort((a, b) => a.label.localeCompare(b.label)));
            } catch (error) {
                console.error('Failed to fetch markers for relation:', error);
            }
        }

        fetchMarkersForRelation();
    }, [publicationId]);

    if (loading) {
        return <div>Loading...</div>;
    }

    return <>
        <table className="searchresults groupstripes-hover" style={{width: '100%'}}>
            <thead></thead>
            <tbody>
            <tr className="table-header">
                <td>Construct</td>
                <td>Type</td>
                <td>Relationship</td>
                <td>Target</td>
                <td>Delete</td>
            </tr>
            {constructRelationshipRows.map(rel => (
                <tr className="experiment-row" key={rel.zdbID}>
                    <td>
                        <div className="gwt-HTML">
                            {rel.constructLabel !== '' && <a href={`/${rel.constructZdbID}`} title={rel.constructLabel}>{rel.constructLabel}</a>}
                        </div>
                    </td>
                    <td>
                        <div className="gwt-Label">{rel.constructType}</div>
                    </td>
                    <td>
                        <div className="gwt-Label">{rel.relationshipType}</div>
                    </td>
                    <td>
                        <div className="gwt-HTML">
                            <a href={`/${rel.markerZdbID}`} id={rel.markerZdbID} title={rel.markerLabel}>
                                <span className="genedom" title={rel.markerLabel} id="Gene Symbol">{rel.markerLabel}</span>
                            </a>
                        </div>
                    </td>
                    <td>
                        {rel.relationshipType == RELATIONSHIP_TO_ADD &&
                        <button type="button" className="gwt-Button">X</button>}
                    </td>
                </tr>
            ))}
            <tr className="experiment-row">
                <td><select className="gwt-ListBox" name="constructToAddList">
                    <option value="-----------">-----------</option>
                    {publicationConstructs.map(construct => (
                        <option key={construct.zdbID} value={construct.zdbID}>{construct.label}</option>
                    ))}
                </select></td>
                <td>
                    <div className="gwt-Label"></div>
                </td>
                <td><select className="gwt-ListBox"><option>{RELATIONSHIP_TO_ADD}</option></select></td>
                <td><select className="gwt-ListBox">
                    <option value="-----------">-----------</option>
                    {markersForRelation.map(marker => (
                        <option key={marker.zdbID} value={marker.zdbID}>{marker.label}</option>
                    ))}
                </select></td>
            </tr>
            <tr>
                <td align="left">
                    <button type="button" className="gwt-Button">Add</button>
                </td>
            </tr>
            </tbody>
        </table>
    </>;
}

export default ConstructRelationshipsTable;