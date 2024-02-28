import React, {useState} from 'react';
import ConstructRegulatoryCodingUnitList from './ConstructRegulatoryCodingUnitList';
import {Cassette, ConstructComponent} from './ConstructTypes';

interface ConstructCassetteEditorProps {
    publicationId: string;
    onChange: (cassette: Cassette) => void;
}

const ConstructCassetteEditor = ({publicationId, onChange}: ConstructCassetteEditorProps) => {

    const [state, setState] = useState<Cassette>({
        promoter: [],
        coding: []
    });

    const handleRegulatoryCodingUnitChange = (constructComponents: ConstructComponent[], type) => {

        //the last item should have its separator set to ''
        const transformedConstructComponents = constructComponents.map((item, index) => {
            if (index === constructComponents.length - 1) {
                return {...item, separator: ''};
            }
            return item;
        });

        const newState = {
            ...state,
            [type]: transformedConstructComponents
        }
        setState(newState);

        if (onChange) {
            onChange(newState);
        }
    }

    return <div>
        <b>Promoter</b>
        <ConstructRegulatoryCodingUnitList publicationId={publicationId} onChange={(items) => handleRegulatoryCodingUnitChange(items, 'promoter') }/>

        <b>Coding</b>
        <ConstructRegulatoryCodingUnitList publicationId={publicationId} onChange={(items) => handleRegulatoryCodingUnitChange(items, 'coding') }/>
    </div>;
}


const isValidCassette = (cassette) => {
    if (!cassette) {
        return false;
    }
    if (!cassette.promoter && !cassette.coding) {
        return false;
    }
    if (cassette.promoter.length === 0 && cassette.coding.length === 0) {
        return false;
    }
    return true;
}

export default ConstructCassetteEditor;
export {isValidCassette};