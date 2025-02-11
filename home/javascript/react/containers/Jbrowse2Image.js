import React, {useRef, useState} from 'react';
import PropTypes from 'prop-types';
import NoData from '../components/NoData';

import assembly from '../constants/GRCz11_assembly.json';
import tracks from '../constants/GRCz11_tracks.json';
import {createViewState, JBrowseLinearGenomeView} from '@jbrowse/react-linear-genome-view';

const IMAGE_SIZE_STEP = 100;
const IMAGE_MAX_WIDTH = 1600;
const IMAGE_MIN_WIDTH = 300;
const DEBOUNCE_INTERVAL = 250;

function randomIdString() {
    const stringLength = 20;
    let id = "";
    const characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
    const numCharacters = characters.length;
    while (id.length < stringLength) {
        const randomIndex = Math.floor(Math.random() * numCharacters);
        id += characters[randomIndex];
    }
    return id;
}

const Jbrowse2Image = ({imageUrl, linkUrl, build, chromosome, height = '400', landmark}) => {
    const [width, setWidth] = useState('1000');
    const containerRef = useRef(null);

    console.log('jbrowse2image arguments');
    console.log({imageUrl, linkUrl, build, chromosome, height, landmark});

    if (!imageUrl) {
        return <NoData/>;
    }

    const state = new createViewState({
        assembly,
        tracks,
        location: landmark,
        defaultSession: {
            id: randomIdString(),
            name: 'zfin embedded session',
            view: {
                id: randomIdString(),
                type: 'LinearGenomeView',
                tracks: [
                    {
                        id: randomIdString(),
                        type: 'FeatureTrack',
                        configuration: 'zfin_transcript',
                        displays: [
                            {
                                id: randomIdString(),
                                type: 'LinearBasicDisplay',
                                configuration: 'transcript-1687907635485-LinearBasicDisplay',
                            },
                        ],
                    },
                    {
                        id: randomIdString(),
                        type: 'FeatureTrack',
                        configuration: 'zfin_features',
                        displays: [
                            {
                                id: randomIdString(),
                                type: 'LinearBasicDisplay',
                                configuration: 'zfin_features-1687908884139-LinearBasicDisplay'
                            }
                        ]
                    },
                    {
                        id: randomIdString(),
                        type: 'FeatureTrack',
                        configuration: 'zfin_gene',
                        displays: [
                            {
                                id: randomIdString(),
                                type: 'LinearBasicDisplay',
                                configuration: 'zfin_gene-1687907419159-LinearBasicDisplay'
                            }
                        ]
                    }
                ],
            },
        },
    });

    return (
        <div className='position-relative'>
            <div ref={containerRef}>
                {build && <div><span className='gbrowse-source-label'>Genome Build: {build}</span><span className='gbrowse-source-label'>Chromosome: {chromosome}</span></div>}
                <JBrowseLinearGenomeView viewState={state} />
            </div>
        </div>
    );
};

Jbrowse2Image.propTypes = {
    imageUrl: PropTypes.string.isRequired,
    linkUrl: PropTypes.string.isRequired,
    height: PropTypes.string,
    build: PropTypes.string,
    chromosome: PropTypes.string,
    landmark: PropTypes.string,
};

export default Jbrowse2Image;
