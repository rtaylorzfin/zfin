import React from 'react';
import {useCurateConstructEditContext} from "./CurateConstructEditContext";

function CurateConstructSynonymEditor() {

    const {state, setStateByProxy} = useCurateConstructEditContext();

    function setSynonymTextValue(value) {
        setStateByProxy(proxy => {proxy.stagedSynonym = value;});
    }

    function handleRemoveSynonym(index) {
        const newSynonyms = [...state.selectedConstruct.synonyms];
        newSynonyms.splice(index, 1);
        setStateByProxy(proxy => {proxy.selectedConstruct.synonyms = newSynonyms;});
    }

    function handleAddSynonym() {
        setStateByProxy(proxy => {
            if (state.stagedSynonym) {
                proxy.selectedConstruct.synonyms.push({label: state.stagedSynonym, zdbID: null});
            }
        });
        setSynonymTextValue('');
    }

    function handleAddSynonymKeyDown(event) {
        if (event.key === 'Enter') {
            handleAddSynonym();
        }
    }

    return <>
        {state.selectedConstruct.synonyms && state.selectedConstruct.synonyms.map((synonym, index) => {
            return <div key={index}>
                <span dangerouslySetInnerHTML={{__html: synonym.label}}></span>{' '}
                <a className='delete fa-trash fa' href='#' onClick={() => handleRemoveSynonym(index)}></a>
            </div>
        })}
        <input
            autoComplete="off"
            type="text"
            size="50"
            value={state.stagedSynonym}
            onChange={e => setSynonymTextValue(e.target.value)}
            onKeyDown={handleAddSynonymKeyDown}
        />
        <input type='button'
               value='+'
               disabled={!state.stagedSynonym}
               onClick={handleAddSynonym}
               />
        </>;
}

export default CurateConstructSynonymEditor;