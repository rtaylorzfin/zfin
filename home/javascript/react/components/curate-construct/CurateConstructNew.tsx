import React, {useState} from 'react';
import ConstructCassetteListEditor, {cassetteHumanReadableList} from './ConstructCassetteListEditor';

/*
 * This component is used to create a new construct
 * The form eventually will be submitted to the server
 * using an object like the following for Tg5(tdg.1-Hsa.TEST1:EGFP,tdg.2-Hsa.TEST2:EGFP):
 *
 * {
 *   "typeAbbreviation": "Tg",
 *   "prefix": "5",
 *   "cassettes": [
 *     {
 *       "cassetteNumber": 1,
 *       "promoterParts": [
 *         "tdg.1",
 *         "-",
 *         "Hsa.TEST1"
 *       ],
 *       "codingParts": [
 *         "EGFP"
 *       ]
 *     },
 *     {
 *       "cassetteNumber": 2,
 *       "promoterParts": [
 *         ",",
 *         "tdg.2",
 *         "-",
 *         "Hsa.TEST2"
 *       ],
 *       "codingParts": [
 *         "EGFP"
 *       ]
 *     }
 *   ]
 * }
 */

interface CurateConstructNewProps {
    publicationId: string;
    show: boolean;
}

const CurateConstructNew = ({publicationId, show= true}: CurateConstructNewProps) => {

    const [display, setDisplay] = useState(show);
    const [chosenType, setChosenType] = useState('Tg');
    const [prefix, setPrefix] = useState('');
    const [synonym, setSynonym] = useState('');
    const [sequence, setSequence] = useState('');
    const [publicNote, setPublicNote] = useState('');
    const [curatorNote, setCuratorNote] = useState('');
    const [cassettes, setCassettes] = useState([]);
    const [cassettesDisplay, setCassettesDisplay] = useState('');

    const toggleDisplay = () => setDisplay(!display);

    const handleCassettesChanged = (cassettesChanged) => {
        console.log('cassettesChanged', cassettesChanged);
        setCassettes(cassettesChanged);
        setCassettesDisplay(cassetteHumanReadableList(cassettesChanged));
    }

    const submitForm = async () => {
        console.log('submitForm');
        const submissionObject = {
            constructName: {
                typeAbbreviation: chosenType,
                prefix: prefix,
                cassettes: cassettes
            },
            synonym: synonym,
            sequence: sequence,
            publicNote: publicNote,
            curatorNote: curatorNote
        }
        console.log('submissionObject', submissionObject);

        //post with fetch to `/action/construct/create`
        const result = await fetch('/action/construct/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(submissionObject),
        });
        const body = await result.json();
        console.log('body', body);
        clearForm();

    }

    const clearForm = () => {
        setChosenType('Tg');
        setPrefix('');
        setSynonym('');
        setSequence('');
        setPublicNote('');
        setCuratorNote('');
        setCassettesDisplay('');
    }

    return <>
        <div className='mb-3'>
            <span className='bold'>CREATE NEW CONSTRUCT: </span>
            <a onClick={toggleDisplay} style={{textDecoration: 'underline'}}>{display ? 'Hide' : 'Show'}</a>
        </div>
        {display &&
        <div className='mb-3' style={{backgroundColor: '#eee'}}>
            <table>
                <thead/>
                <tbody>
                    <tr>
                        <td><b>Construct Type</b></td>
                        <td>
                            {/*Select dropdown associated with React const chosenType (Tg, Et, Gt, Pt)*/}
                            <select value={chosenType} onChange={e => setChosenType(e.target.value)}>
                                <option value='Tg'>Tg</option>
                                <option value='Et'>Et</option>
                                <option value='Gt'>Gt</option>
                                <option value='Pt'>Pt</option>
                            </select>
                            <label htmlFor='prefix'><b>Prefix:</b></label>
                            <input
                                id='prefix'
                                size='15'
                                className='prefix'
                                name='prefix'
                                value={prefix}
                                onChange={e => setPrefix(e.target.value)}
                                type='text'
                            />
                        </td>
                    </tr>
                    <tr>
                        <td><b>Synonym</b>:</td>
                        <td><input autoComplete='off' type='text' size='50' value={synonym} onChange={e => setSynonym(e.target.value)}/></td>
                    </tr>
                    <tr>
                        <td><b>Sequence</b>:</td>
                        <td><input autoComplete='off' type='text' size='50' value={sequence} onChange={e => setSequence(e.target.value)}/></td>
                    </tr>
                    <tr>
                        <td><b>Public Note</b>:</td>
                        <td>
                            <textarea rows='3' cols='50' value={publicNote} onChange={e => setPublicNote(e.target.value)}/>
                        </td>
                        <td><b>Curator Note</b>:</td>
                        <td>
                            <textarea rows='3' cols='50' value={curatorNote} onChange={e => setCuratorNote(e.target.value)}/>
                        </td>
                    </tr>
                </tbody>
            </table>
            <div className='mb-3'>
                <ConstructCassetteListEditor publicationId={publicationId} onChange={handleCassettesChanged}/>
            </div>
            <div className='mb-3'>
                <p>
                    <b>Display Name:</b>
                    <input name='constructDisplayName' disabled='disabled' type='text' value={chosenType + prefix + '(' + cassettesDisplay + ')'} size='150'/>
                </p>
            </div>
            <div className='mb-3'>
                <button type='button' className='mr-2' onClick={submitForm}>Create</button>
                <button type='button' onClick={clearForm}>Cancel</button>
            </div>
        </div>}
    </>;
}

export default CurateConstructNew;