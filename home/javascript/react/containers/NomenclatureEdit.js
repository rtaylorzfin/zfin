import React, {useState} from 'react';
import PropTypes from 'prop-types';
import EditNomenclatureModal from "../components/EditNomenclatureModal";


const NomenclatureEdit = ({markerId, markerHistoryJson, markerReasonsJson, hasRoot}) => {
    console.log('markerReasonsJson', markerReasonsJson);
    const [showAllEvents, setShowAllEvents] = useState(false);
    const [markerHistory, setMarkerHistory] = useState(JSON.parse(markerHistoryJson));
    const [markerReasons, setMarkerReasons] = useState(JSON.parse(markerReasonsJson));
    const [rootAccess, setRootAccess] = useState(!!hasRoot);
    const [enableEditing, setEnableEditing] = useState(false);
    const [showModal, setShowModal] = useState(false);

    if (markerHistory === null) {
        setMarkerHistory([]);
    }

    console.log('markerHistory', markerHistory);
    console.log('markerReasons', markerReasons);
    console.log('hasRoot', hasRoot);
    console.log('rootAccess', rootAccess);

    function toggleShowAllEvents(event) {
        event.preventDefault();
        setShowAllEvents(!showAllEvents);
    }

    function handleEnableEditingClick(event) {
        event.preventDefault();
        setEnableEditing(!enableEditing);
    }

    function handleEditClicked(event) {
        event.preventDefault();
        setShowModal(true);
    }

    return <>

        <table className="data_manager">
            <tbody>
            <tr><td><b>ZFIN ID:</b> {markerId}</td>
                {rootAccess && <td><a href="#" onClick={handleEnableEditingClick} className="root">Edit</a></td>}
                <td><a href={'/action/updates/' + markerId}>Last Update: { markerHistory.length > 0 ? markerHistory[markerHistory.length - 1].date : '' }</a></td>
            </tr>
            </tbody>
        </table>

        <div className="summaryTitle">Nomenclature History</div>

        {rootAccess &&
            (showAllEvents ?
                <span id="showReducedEventsToggle"><a href="#" onClick={toggleShowAllEvents}>Hide Naming Events</a></span> :
                <span id="showAllEventsToggle"><a href="#" onClick={toggleShowAllEvents}>Show All Events</a></span>)
        }

        <table className="summary sortable">
            <thead>
                <tr>
                    {enableEditing && <th>Edit</th>}
                    <th>New Value</th>
                    <th>Event</th>
                    <th>Old Value</th>
                    <th>Date</th>
                    <th>Reason</th>
                    <th>Comments</th>
                </tr>
            </thead>
            <tbody>

            {markerHistory.filter(h => showAllEvents || h.eventName !== 'renamed').map((history, index) => (
                <tr key={history.zdbID} id={'all_' + index}>
                    {enableEditing &&
                    <td>
                        <span><a onClick={handleEditClicked} href='#'>Edit</a></span>
                    </td>}
                    <td><span className="genedom">{history.newValue}</span></td>
                    <td>{history.eventDisplay}</td>
                    <td>
                        <span className="genedom">{history.oldSymbol}</span>
                    </td>
                    <td>
                        {history.date}
                    </td>
                    <td>{history.reason}
                        {history.attributionsSize == 1 &&
                            (<a href={'/' + history.firstPublication}>1</a>)
                        }
                        {history.attributionsSize > 1 &&
                            (<a href={'/action/publication/list/' + history.zdbID}>{history.attributionsSize}</a>)
                        }
                    </td>
                    <td>{history.comments}</td>
                </tr>
            ))}

            </tbody>
        </table>
        <EditNomenclatureModal show={showModal} onHide={() => {}} />
    </>;
};

NomenclatureEdit.propTypes = {
    markerId: PropTypes.string,
    markerHistoryJson: PropTypes.string,
    markerReasonsJson: PropTypes.string,
    hasRoot: PropTypes.string,
}

export default NomenclatureEdit;
