import React, {useState} from 'react';
import PropTypes from 'prop-types';
import SequenceTargetingReagentSequenceFields from '../components/marker-edit/SequenceTargetingReagentSequenceFields';
import {useForm} from "react-form";

const SequenceTargetingReagentNewSequence = ({ strType }) => {

    const isTalen = strType === 'TALEN';
    let validBases = 'ATGC';
    if (isTalen) {
        validBases += 'R';
    }
    const reportedLabel = isTalen ? 'Target Sequence 1 Reported' : 'Reported';

    const [strValues, setStrValues] = useState({
        reportedSequence1: '',
        sequence1: '',
        reversed1: false,
        complemented1: false,
        reportedSequence2: '',
        sequence2: '',
        reversed2: false,
        complemented2: false,
    });

    const {
        Form,
        reset,
        setFieldValue,
        setMeta,
        values,
        meta: { isValid, isSubmitting, isSubmitted, serverError }
    } = useForm({
        defaultValues: strValues,
        onSubmit: async (values) => {
            console.log('onSubmit', values);
        },
    });

    return (
        <Form>
            <SequenceTargetingReagentSequenceFields
                complementedField='complemented1'
                displayedSequenceField='sequence1'
                reportedLabel={reportedLabel}
                displayedLabel='Displayed'
                reportedSequenceField='reportedSequence1'
                reversedField='reversed1'
                validBases={validBases}
                values={values}
                setDisplayedSequence={value => setFieldValue('sequence1', value)}
                newRow={true}
            />

            {isTalen &&
            <div className='mt-4'>
                <SequenceTargetingReagentSequenceFields
                    complementedField='complemented2'
                    displayedSequenceField='sequence2'
                    reportedLabel='Target Sequence 2 Reported'
                    displayedLabel='Displayed'
                    reportedSequenceField='reportedSequence2'
                    reversedField='reversed2'
                    validBases={validBases}
                    values={values}
                    setDisplayedSequence={value => setFieldValue('sequence2', value)}
                    newRow={true}
                />
            </div>
            }

        </Form>
    );
};

SequenceTargetingReagentNewSequence.propTypes = {
    strType: PropTypes.string,
};

export default SequenceTargetingReagentNewSequence;
