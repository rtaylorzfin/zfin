import React, {useEffect, useState} from "react";
import PropTypes from "prop-types";
import MarkerInput from "../components/form/MarkerInput";

const NewSequenceTargetingReagentForm = ({ pubId: defaultPubId, strType: defaultStrType, strTypesJson }) => {
    const strTypes = JSON.parse(strTypesJson);
    const [strType, setStrType] = useState(defaultStrType);
    const [pubId, setPubId] = useState(defaultPubId);
    const [computedName, setComputedName] = useState("");
    const [alias, setAlias] = useState("");
    const [targetGenes, setTargetGenes] = useState([]);
    const [targetGene, setTargetGene] = useState("");
    const [stagedGene, setStagedGene] = useState("");

    function handleGeneChange(event) {
        console.log('handleGeneChange targetGenes');
        if (event.type === "typeahead:select") {
            setStagedGene(event.target.value);
            setTargetGene("");
        } else {
            setTargetGene(event.target.value);
        }
    }

    function handleGeneDelete(event, gene) {
        event.preventDefault();
        setTargetGenes(targetGenes.filter(g => g !== gene));
    }

    function computeName(newType, newGenes) {
        const combinedGenes = newGenes.join(',');
        fetch(`/action/marker/propose-name-by-type-and-genes?type=${newType}&genes=${combinedGenes}`)
            .then(response => response.text())
            .then(data => setComputedName(data));
    }

    useEffect(() => {
        if (stagedGene === "") {
            return;
        }
        setTargetGenes([...targetGenes, stagedGene]);
        setStagedGene("");
    }, [stagedGene]);

    useEffect(() => {
        computeName(strType, targetGenes);
    }, [strType, targetGenes]);

    return (
        <form id="str-form" className="form-horizontal" action="sequence-targeting-reagent-add" method="post">
            <div className="form-group row">
                <label htmlFor="publicationID" className="col-md-2 col-form-label">Reference</label>
                <div className="col-md-4">
                    <input id="publicationID" name="publicationID" placeholder="ZDB-PUB-123456-7"
                           className="form-control" type="text" value={pubId} onChange={e => setPubId(e.target.value)}
                    />
                </div>
            </div>
            <div className="form-group row">
                <label htmlFor="strType" className="col-md-2 col-form-label">Type</label>
                <div className="col-md-4">
                    <select id="strType" name="strType" className="form-control"
                            value={strType} onChange={e => setStrType(e.target.value)}>
                        <option value="" disabled="disabled">Select...</option>
                        {Object.keys(strTypes).map(key => <option key={key} value={key}>{strTypes[key]}</option>)}
                    </select>
                </div>
            </div>

            <div className="form-group row">
                <label htmlFor="targetGene" className="col-md-2 col-form-label">Target Gene(s)</label>
                <div className="col-md-4">
                    <MarkerInput typeGroup={'GENEDOM_AND_NTR'}
                                 typeGroup2={'GENEDOM_AND_NTR'}
                                 id="targetGene"
                                 name="targetGene"
                                 className="form-control"
                                 value={targetGene}
                                 onChange={(e) => {handleGeneChange(e)}} />
                </div>
            </div>
            <div className="row">
                <div className="col-md-2">
                </div>
                <div className="col-md-4">
                    <ul className="list-unstyled">
                        {targetGenes.map(gene => <li key={gene}>{gene} <a href='#' onClick={e => {handleGeneDelete(e, gene)}}><i className='fa fa-trash'/></a></li>)}
                    </ul>
                </div>
            </div>

            <div className="form-group row">
                <label htmlFor="name" className="col-md-2 col-form-label">Name</label>
                <div className="col-md-4">
                    <input id="name" name="name" className="form-control" type="text" value={computedName} readOnly={true}/>

                </div>
            </div>
            <div className="form-group row">
                <label htmlFor="alias" className="col-md-2 col-form-label">Alias</label>
                <div className="col-md-4">
                    <input id="alias" name="alias" className="form-control" type="text" value={alias} onChange={e => setAlias(e.target.value)}/>

                </div>
            </div>
            <div><h2>TODO: add sequence info</h2></div>
            <div className="form-group row">
                <label htmlFor="publicNote" className="col-md-2 col-form-label">Public Note</label>
                <div className="col-md-6">
                    <textarea id="publicNote" name="publicNote" className="form-control" rows="3"></textarea>
                </div>
            </div>
            <div className="form-group row">
                <label htmlFor="curatorNote" className="col-md-2 col-form-label">Curator Note</label>
                <div className="col-md-6">
                    <textarea id="curatorNote" name="curatorNote" className="form-control" rows="3"></textarea>
                </div>
            </div>
            <div className="form-group row">
                <div className="offset-md-2 col-md-10">
                    <button type="submit" className="btn btn-primary">Submit</button>
                </div>
            </div>
        </form>
    );
}


NewSequenceTargetingReagentForm.propTypes = {
    pubId: PropTypes.string,
    strType: PropTypes.string,
    strTypesJson: PropTypes.string,
};

export default NewSequenceTargetingReagentForm;
