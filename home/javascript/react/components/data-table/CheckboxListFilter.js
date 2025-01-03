import React from 'react';
import PropTypes from 'prop-types';
import CheckboxList from '../CheckboxList';

const DELIMITER = '|';

const CheckboxListFilter = ({value, onChange, options, displayFunction}) => {
    return (
        <CheckboxList
            getItemDisplay={displayFunction ? displayFunction :  (optionName) => optionName }
            items={options}
            value={value ? value.split(DELIMITER) : []}
            onChange={(values) => onChange(values.join(DELIMITER))}
        />
    );
}

CheckboxListFilter.propTypes = {
    displayFunction: PropTypes.func,
    onChange: PropTypes.func,
    options: PropTypes.array,
    value: PropTypes.string,
};

export default CheckboxListFilter;
