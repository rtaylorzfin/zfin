import React, {useLayoutEffect, useRef, useState} from 'react';
import PropTypes from 'prop-types';
import NoData from '../components/NoData';

import assembly from '../constants/GRCz11_assembly';
import tracks from '../constants/GRCz11_tracks';
import { createViewState, JBrowseLinearGenomeView } from '@jbrowse/react-linear-genome-view';

const IMAGE_SIZE_STEP = 100;
const IMAGE_MAX_WIDTH = 1600;
const IMAGE_MIN_WIDTH = 300;
const DEBOUNCE_INTERVAL = 250;

const Jbrowse2Image = ({imageUrl, linkUrl, build, chromosome, height = '400'}) => {
    const [width, setWidth] = useState('1000');
    const containerRef = useRef(null);

    if (!imageUrl) {
        return <NoData/>;
    }

    const state = new createViewState({
        assembly,
        tracks,
        location: "18:20,676,475..20,723,994",
        defaultSession: {
            id: "xuiW7e-84l-WyGvJThU1o",
            name: "zfin embedded session",
            view: {
                id: "gxMQbZE1BobjoKsdDLeNw",
                type: "LinearGenomeView",
                tracks: [
                    {
                        id: "kQkVaBRWUDUWk7KgE4YFT",
                        type: "FeatureTrack",
                        configuration: "zfin_transcript",
                        displays: [
                            {
                                id: "YdoEISkbaWh2Wr_sEhYpi",
                                type: "LinearBasicDisplay",
                                configuration:
                                    "transcript-1687907635485-LinearBasicDisplay",
                            },
                        ],
                    },
                ],
            },
        },
    });

    return (
        <div className='position-relative'>
            <div ref={containerRef}>
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
};

export default Jbrowse2Image;
