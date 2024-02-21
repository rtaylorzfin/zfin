import React from 'react';
import PropTypes from 'prop-types';

const ConstructCassetteView = ({cassette}) => {
    return <>
        <b>Promoter: </b>
        {cassette.promoter.map((item, index) => {
            return <React.Fragment key={index}>
                <span className='promoter'>{item.value}</span>
                <span className='separator'>{item.separator}</span>
            </React.Fragment>})}
        <b> Coding: </b>
        {cassette.coding.map((item, index) => {
            return <React.Fragment key={index}>
                <span className='coding'>{item.value}</span>
                <span className='separator'>{item.separator}</span>
            </React.Fragment>})}
    </>
}

ConstructCassetteView.propTypes = {
    cassette: PropTypes.object,
}

export default ConstructCassetteView;
