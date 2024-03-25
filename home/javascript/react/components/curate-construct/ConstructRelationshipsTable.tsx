import React, {useEffect} from 'react';

interface ConstructRelationshipsTableProps {
    publicationId: string;
}

//type for rows of table:
type ConstructRelationshipRow = {
    zdbID: string;
    constructZdbID: string;
    constructType: string;
    constructName: string;
    constructLabel: string;
    relationshipType: string;
    markerLabel: string;
    markerZdbID: string;
}

//what the server sends back after creating a new relationship
type NewConstructRelationshipServerResponse = {
    type: string;
    zdbID: string;
    construct: {
        zdbID: string;
        name: string;
    };
    marker: {
        abbreviation: string;
        zdbID: string;
        name: string;
    };
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

const ConstructRelationshipsTable = ({publicationId}: ConstructRelationshipsTableProps) => {
    const [loading, setLoading] = React.useState<boolean>(true);
    const [constructRelationshipRows, setConstructRelationshipRows] = React.useState<ConstructRelationshipRow[]>([]);
    const [publicationConstructs, setPublicationConstructs] = React.useState<MarkerNameAndZdbId[]>([]);
    const [markersForRelation, setMarkersForRelation] = React.useState<MarkerNameAndZdbId[]>([]);
    const [selectedMarker, setSelectedMarker] = React.useState<string>('');
    const [selectedConstruct, setSelectedConstruct] = React.useState<string>('');
    const RELATIONSHIP_TO_ADD = 'contains region';

    function sortConstructRelationshipRows(constructRelationshipRows: ConstructRelationshipRow[]) {
        const sortedRows = constructRelationshipRows.sort((a, b) => {
            if (a.constructName === b.constructName) {
                if (a.relationshipType === b.relationshipType) {
                    return a.markerLabel.localeCompare(b.markerLabel);
                }
                return a.relationshipType.localeCompare(b.relationshipType);
            }
            return a.constructName.localeCompare(b.constructName);
        });
        return removeRedundantLabels(sortedRows);
    }

    function removeRedundantLabels(constructRelationshipRows: ConstructRelationshipRow[]) {
        let lastConstructName = '';

        return constructRelationshipRows.map( (rel) => {
            if (rel.constructName === lastConstructName) {
                return {...rel, constructLabel: ''};
            }
            lastConstructName = rel.constructName;
            return rel;
        });
    }

    async function submitConstructRelationship (constructZdbID: string, markerZdbID: string, relationshipType: string, publicationZdbID: string) {
        try {
            const response = await fetch(`${calculatedDomain}/action/api/construct/${constructZdbID}/relationships`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    firstMarker: {zdbID: constructZdbID},
                    secondMarker: {zdbID: markerZdbID},
                    markerRelationshipType: {name: relationshipType},
                    references: [{zdbID: publicationZdbID}]
                }),
            });
            return await response.json();
        } catch (error) {
            console.error('Failed to submit construct relationship:', error);
        }
    }

    async function insertNewRelationshipRow(serverResponseData: NewConstructRelationshipServerResponse) {
        const {zdbID, construct, marker} = serverResponseData;

        //insert the new row at the correct position in the table (sorted by constructName, then by relationshipType, then by markerLabel)
        const newRelationshipRow: ConstructRelationshipRow = {
            zdbID,
            constructZdbID: construct.zdbID,
            constructType: '',
            constructName: construct.name,
            constructLabel: construct.name,
            relationshipType: RELATIONSHIP_TO_ADD,
            markerLabel: marker.abbreviation,
            markerZdbID: marker.zdbID
        };

        const newRows = sortConstructRelationshipRows([...constructRelationshipRows, newRelationshipRow]);
        setConstructRelationshipRows(newRows);
    }

    async function removeRelationshipRow(row: ConstructRelationshipRow) {
        const newRows = constructRelationshipRows.filter((rel) => rel.zdbID !== row.zdbID);
        setConstructRelationshipRows(newRows);
    }

    async function fetchConstructRelationships() {
        setLoading(true); // Assuming you want to set loading to true at the beginning of the fetch
        try {
            const response = await fetch(`${calculatedDomain}/action/api/publication/${publicationId}/constructs`);
            const constructsData = await response.json();
            let uniqueConstructsMap = {};

            let uniqueConstructs = [];

            const mappedConstructRelationships = constructsData.map(({ zdbID, constructDTO, markerDTO, relationshipType }) => {
                const { zdbID: constructZdbID, constructType: constructType, name: constructName } = constructDTO;
                const { label: markerLabel, zdbID: markerZdbID } = markerDTO;

                if (uniqueConstructsMap[constructZdbID] === undefined) {
                    uniqueConstructs.push({label: constructName, zdbID: constructZdbID});
                }
                uniqueConstructsMap[constructZdbID] = constructName;

                const constructLabel = constructName;

                return {
                    zdbID,
                    constructZdbID,
                    constructType,
                    constructName,
                    constructLabel,
                    relationshipType,
                    markerLabel,
                    markerZdbID,
                };
            });

            //sort by constructName, then by relationshipType, then by markerLabel
            const sortedMappedConstructRelationships = sortConstructRelationshipRows(mappedConstructRelationships);

            setPublicationConstructs(uniqueConstructs.sort((a, b) => a.label.localeCompare(b.label)));
            setConstructRelationshipRows(sortedMappedConstructRelationships);
        } catch (error) {
            console.error('Failed to fetch construct relationships:', error);
        } finally {
            setLoading(false);
        }
    }

    async function fetchMarkersForRelation() {
        try {
            const response = await fetch(`${calculatedDomain}/action/api/publication/${publicationId}/${RELATIONSHIP_TO_ADD}/markersForRelation`);
            const markersData = await response.json();

            setMarkersForRelation(markersData.map(({ label, zdbID }) => ({ label, zdbID })).sort((a, b) => a.label.localeCompare(b.label)));
        } catch (error) {
            console.error('Failed to fetch markers for relation:', error);
        }
    }

    async function deleteConstructMarkerRelationship(row: ConstructRelationshipRow) {
        const {constructZdbID, zdbID} = row;
        await fetch(`${calculatedDomain}/action/api/construct/${constructZdbID}/relationships/${zdbID}`, {
            method: 'DELETE'
        });
    }

    async function handleAddButton(event: React.MouseEvent<HTMLButtonElement, MouseEvent>) {
        event.preventDefault();
        if (selectedConstruct === '' || selectedMarker === '') {
            return;
        }
        const newRelationshipFromServer = await submitConstructRelationship(selectedConstruct, selectedMarker, RELATIONSHIP_TO_ADD, publicationId);
        insertNewRelationshipRow(newRelationshipFromServer);
    }


    async function handleDeleteButton(rel: ConstructRelationshipRow) {
        await deleteConstructMarkerRelationship(rel);
        removeRelationshipRow(rel);
    }

    useEffect(() => {
        fetchConstructRelationships();
    }, [publicationId]);

    useEffect(() => {
        fetchMarkersForRelation();
    }, [publicationId]);

    if (loading) {
        return <div>Loading...</div>;
    }

    return <>
        <table className='searchresults groupstripes-hover' style={{width: '100%'}}>
            <thead></thead>
            <tbody>
            <tr className='table-header'>
                <td>Construct</td>
                <td>Type</td>
                <td>Relationship</td>
                <td>Target</td>
                <td>Delete</td>
            </tr>
            {constructRelationshipRows.map(rel => (
                <tr className='experiment-row' key={rel.zdbID}>
                    <td>
                        <div className='gwt-HTML'>
                            {rel.constructLabel !== '' && <a href={`/${rel.constructZdbID}`} title={rel.constructLabel}>{rel.constructLabel}</a>}
                        </div>
                    </td>
                    <td>
                        <div className='gwt-Label'>Transgenic Construct</div>
                    </td>
                    <td>
                        <div className='gwt-Label'>{rel.relationshipType}</div>
                    </td>
                    <td>
                        <div className='gwt-HTML'>
                            <a href={`/${rel.markerZdbID}`} id={rel.markerZdbID} title={rel.markerLabel}>
                                <span className='genedom' title={rel.markerLabel} id='Gene Symbol'>{rel.markerLabel}</span>
                            </a>
                        </div>
                    </td>
                    <td>
                        {rel.relationshipType == RELATIONSHIP_TO_ADD &&
                        <button type='button' className='gwt-Button' onClick={() => handleDeleteButton(rel)}>X</button>}
                    </td>
                </tr>
            ))}
            <tr className='experiment-row'>
                <td><select className='gwt-ListBox' name='constructToAddList' onChange={(e) => setSelectedConstruct(e.target.value)}>
                    <option value='-----------'>-----------</option>
                    {publicationConstructs.map(construct => (
                        <option key={construct.zdbID} value={construct.zdbID}>{construct.label}</option>
                    ))}
                </select></td>
                <td>
                    <div className='gwt-Label'></div>
                </td>
                <td><select className='gwt-ListBox'><option>{RELATIONSHIP_TO_ADD}</option></select></td>
                <td><select className='gwt-ListBox' onChange={(e) => setSelectedMarker(e.target.value)}>
                    <option value='-----------'>-----------</option>
                    {markersForRelation.map(marker => (
                        <option key={marker.zdbID} value={marker.zdbID}>{marker.label}</option>
                    ))}
                </select></td>
            </tr>
            <tr>
                <td align='left'>
                    <button type='button' className='gwt-Button' onClick={handleAddButton}>Add</button>
                </td>
            </tr>
            </tbody>
        </table>
    </>;
}

export default ConstructRelationshipsTable;