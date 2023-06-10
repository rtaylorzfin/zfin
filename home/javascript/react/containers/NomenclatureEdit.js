import React, {useState} from 'react';
import PropTypes from 'prop-types';


const NomenclatureEdit = ({markerId, markerHistoryJson, markerReasons, hasRoot}) => {

    const [showAllEvents, setShowAllEvents] = useState(false);

    console.log('markerHistory', markerHistoryJson);

    const [markerHistory, setMarkerHistory] = useState(JSON.parse(markerHistoryJson));
    console.log('markerHistory', markerHistory);

    function toggleShowAllEvents(event) {
        event.preventDefault();
        setShowAllEvents(!showAllEvents);
    }

    return <>

        <table className="data_manager">
            <tbody>
            <tr><td><b>ZFIN ID:</b> ZDB-TODO</td>
                <td><a href="#javascript:editNomenclature();" className="root">Edit</a></td>
                <td><a href="/action/updates/ZDB-GENE-990415-8">Last Update:</a></td>
            </tr>
            </tbody>
        </table>

        <div className="summaryTitle">Nomenclature History</div>

        {hasRoot &&
            showAllEvents ?
                <span id="showReducedEventsToggle"><a href="#" onClick={toggleShowAllEvents}>Hide Naming Events</a></span> :
                <span id="showAllEventsToggle"><a href="#" onClick={toggleShowAllEvents}>Show All Events</a></span>
        }

        <table className="summary sortable">
            <thead>
                <tr>
                    <th id="edit_" style={{display: 'none'}}>Edit</th>
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
                    <td id={'edit_' + index} style={{display: 'none'}}>
                        <span onClick={() => alert('TODO: open editor')}><a href='#'>Edit</a></span>
                    </td>
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
    </>;
};

NomenclatureEdit.propTypes = {
    markerId: PropTypes.string,
    markerHistoryJson: PropTypes.string,
    markerReasons: PropTypes.array,
    hasRoot: PropTypes.string,
}

export default NomenclatureEdit;
