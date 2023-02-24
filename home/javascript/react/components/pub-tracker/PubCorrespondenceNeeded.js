import React from 'react';
import PropTypes from 'prop-types';
import useFetch from "../../hooks/useFetch";
import LoadingSpinner from "../LoadingSpinner";
import GenericErrorMessage from "../GenericErrorMessage";

const PubCorrespondenceNeeded = ({pubId}) => {
    const url = '/action/api/correspondence/' + pubId;
    const {
        value: correspondenceReasons,
        setValue: setCorrespondenceReasons,
        pending,
        failed,
    } = useFetch(url);
    const onToggleReason = (reason, checked) => {
        console.log('onToggleReason', reason, checked);
        let clone = [ ...correspondenceReasons ];
        clone.find(r => r.id === reason.id).needed = checked;
        setCorrespondenceReasons(clone);
    }

    if (pending) {
        return <LoadingSpinner/>;
    }

    if (failed || !correspondenceReasons) {
        return <GenericErrorMessage/>;
    }

    return <>
        <table className='table col-sm-6'>
            <thead>
            <tr>
                <th></th>
                <th>Reason</th>
            </tr>
            </thead>
            <tbody>
            {correspondenceReasons.map((reason) =>
                <tr key={reason.id}>
                    <td>
                        <input type='checkbox' checked={reason.needed} onChange={event => onToggleReason(reason, event.target.checked)} />
                    </td>
                    <td>{reason.name}</td>
                </tr>
            )}
            </tbody>
        </table>
        {/*<ul className="list-group">*/}
        {/*    <li className="list-group-item rounded-0">*/}
        {/*        <div className="custom-control custom-checkbox border-bottom">*/}
        {/*            <input className="custom-control-input" id="customCheck1" type="checkbox"/>*/}
        {/*                <label className="cursor-pointer font-weight-normal d-block custom-control-label"*/}
        {/*                       htmlFor="customCheck1">Margherita</label>*/}
        {/*        </div>*/}
        {/*        <div className="custom-control custom-checkbox border-bottom">*/}
        {/*            <input className="custom-control-input" id="customCheck1" type="checkbox"/>*/}
        {/*                <label className="cursor-pointer font-weight-normal d-block custom-control-label"*/}
        {/*                       htmlFor="customCheck1">Margherita</label>*/}
        {/*        </div>*/}
        {/*        <div className="custom-control custom-checkbox border-bottom">*/}
        {/*            <input className="custom-control-input" id="customCheck1" type="checkbox"/>*/}
        {/*                <label className="cursor-pointer font-weight-normal d-block custom-control-label"*/}
        {/*                       htmlFor="customCheck1">Margherita</label>*/}
        {/*        </div>*/}
        {/*    </li>*/}
        {/*</ul>*/}
    </>;
};

PubCorrespondenceNeeded.propTypes = {
    pubId: PropTypes.string.isRequired,
};

export default PubCorrespondenceNeeded;
