import React, {useState} from 'react';
import PropTypes from 'prop-types';
import LoadingSpinner from '../components/LoadingSpinner';
import AddSequenceModal from '../components/sequence-edit/AddSequenceModal';
import EditSequenceModal from '../components/sequence-edit/EditSequenceModal';
import DeleteSequenceModal from '../components/sequence-edit/DeleteSequenceModal';
import SequenceInformationTable from '../components/sequence-edit/SequenceInformationTable';
import useFetch from '../hooks/useFetch';
import {stringify} from "qs";

const NomenclatureEdit = ({markerId, markerHistoryJson, markerReasons}) => {

    console.log('markerHistory', markerHistoryJson);

    const [markerHistory, setMarkerHistory] = useState(JSON.parse(markerHistoryJson));
    console.log('markerHistory', markerHistory);

    return <>
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

            {markerHistory.filter(h => h.eventName == 'renamed').map((history, index) => {
            return <tr key={history.zdbID} id={'reduced_' + index}>
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
            })}
            </tbody>
        </table>
    </>;
};

NomenclatureEdit.propTypes = {
    markerId: PropTypes.string,
    markerHistoryJson: PropTypes.string,
    markerReasons: PropTypes.array,
}

export default NomenclatureEdit;
