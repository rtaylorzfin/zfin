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
        <div className="section mb-1">
            <div className='heading'>
                Reasons for Correspondence
            </div>
        </div>
        <div className="row p-2">
            {correspondenceReasons.map((reason) =>
                <div key={reason.id} className="col-sm-6 custom-control custom-checkbox">
                    <input className="custom-control-input" id={'reason-' + reason.id} type="checkbox" onChange={event => onToggleReason(reason, event.target.checked)}/>
                    <label className="cursor-pointer font-weight-normal d-block custom-control-label"
                           htmlFor={'reason-' + reason.id}>{reason.name}</label>
                </div>
            )}
        </div>
    </>;
};

PubCorrespondenceNeeded.propTypes = {
    pubId: PropTypes.string.isRequired,
};

export default PubCorrespondenceNeeded;
