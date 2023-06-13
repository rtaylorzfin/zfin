import React, {useState, useEffect} from 'react';
import PropTypes from 'prop-types';
import Modal from './Modal';
const EditNomenclatureModal = ({markerId, show, onEdit, onClose}) => {

    const [showEditNomenclatureModal, setShowEditNomenclatureModal] = useState(show);
    const [validationErrors, setValidationErrors] = useState({});

    function closeModal() {

    }

    useEffect(() => {
        setShowEditNomenclatureModal(show);
    }, [show]);

    return (
        <Modal open={showEditNomenclatureModal} onClose={closeModal} config={{escapeClose: true, clickClose: true, showClose: true}}>

            <h3>
                Update
            </h3>
            {/*{error message}*/}
            <table>
                <tbody>
                    <tr>
                        <td>Field</td>
                    </tr>
                    <tr>
                        {/*<td>Accession:&nbsp;*/}
                        {/*    <input*/}
                        {/*        value={updateSequenceInfo.accession}*/}
                        {/*        onChange={(e) => {setUpdateSequenceInfo({...updateSequenceInfo, accession: e.target.value})}}*/}
                        {/*    />*/}
                        {/*</td>*/}
                        {/*{validationErrors.accession && <td><span className='error'>{validationErrors.accession}</span></td>}*/}
                    </tr>
                </tbody>
            </table>
            {/*<table>*/}
            {/*    <tbody>*/}
            {/*        { updateSequenceInfo.references && updateSequenceInfo.references.map((ref) => {*/}
            {/*            return <tr key={ref.zdbID}>*/}
            {/*                <td>*/}
            {/*                    <a*/}
            {/*                        target='_blank'*/}
            {/*                        rel='noreferrer'*/}
            {/*                        href={'/' + ref.zdbID}*/}
            {/*                    >{ref.zdbID}</a>*/}
            {/*                </td>*/}
            {/*                <td>*/}
            {/*                    {updateSequenceInfo.references.length > 1 &&*/}
            {/*                        <a*/}
            {/*                            onClick={() => {deleteAttributionFromSequence(ref)}}*/}
            {/*                            href='#'*/}
            {/*                        >*/}
            {/*                            <img alt='Delete' src='/images/delete-button.png'/>*/}
            {/*                        </a>*/}
            {/*                    }*/}
            {/*                </td>*/}
            {/*            </tr>*/}
            {/*        })}*/}
            {/*        <tr>*/}
            {/*            <td>Reference:&nbsp;*/}
            {/*                <input*/}
            {/*                    value={updateSequenceInfo.reference}*/}
            {/*                    onChange={(e) => {setUpdateSequenceInfo({...updateSequenceInfo, reference: e.target.value})}}*/}
            {/*                /></td>*/}
            {/*            {validationErrors.reference && <td><span className='error'>{validationErrors.reference}</span></td>}*/}
            {/*        </tr>*/}
            {/*        <tr>*/}
            {/*            <td colSpan='2'>*/}
            {/*                <button onClick={closeModal} className='zfin-button cancel'>Close</button>{' '}*/}
            {/*                <button onClick={addAttributionToSequence} className='zfin-button approve'>Add</button>*/}
            {/*            </td>*/}
            {/*        </tr>*/}
            {/*    </tbody>*/}
            {/*</table>*/}
        </Modal>
    );

}

EditNomenclatureModal.propTypes = {
    markerId: PropTypes.string,
    show: PropTypes.object,
    onEdit: PropTypes.func,
    onClose: PropTypes.func,
}

export default EditNomenclatureModal;