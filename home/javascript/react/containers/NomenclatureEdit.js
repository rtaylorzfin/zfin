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


    </>;
};

NomenclatureEdit.propTypes = {
    markerId: PropTypes.string,
}

export default NomenclatureEdit;
