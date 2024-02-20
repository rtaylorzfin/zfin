import React, {useState} from 'react';
import type {Suggestion} from './ConstructMarkerAutocomplete';
import ConstructMarkerAutocomplete from './ConstructMarkerAutocomplete';

interface ConstructRegulatoryCodingUnitListProps {
    publicationId: string;
    onChange?: (value: Suggestion[]) => void;
}


/**
 * This component is used to display a list of promoters and a text field to add new promoters.
 * The same functionality can be used for codings (hence the name of the component including Regulatory and Coding).
 * @param publicationId
 * @param onChange
 * @returns JSX Element
 * @constructor
 */
const ConstructRegulatoryCodingUnitList = ({publicationId, onChange}: ConstructRegulatoryCodingUnitListProps) => {
    const [rcUnitItems, setRcUnitItems] = useState<Suggestion[]>([]);
    const defaultSeparator = '-';

    const styles = {
        rcUnitItems: {
            display: 'flex',
            alignItems: 'center',
            gap: '10px'
        }
    };

    const handleAutoCompleteChange = (item : Suggestion) => {
        const itemWithSeparator = {...item, separator: ''};

        if(item.value === '') {
            onChange(rcUnitItems);
            return;
        }

        const newItems = [...rcUnitItems, itemWithSeparator];
        onChange(newItems);
    }

    const handleItemSelected = (item) => {
        const itemWithSeparator = {...item, separator: defaultSeparator};
        const newItems = [...rcUnitItems, itemWithSeparator];
        setRcUnitItemsAndNotify(newItems);
    }

    const handleItemRemoved = (itemToRemove) => {
        const newItems = rcUnitItems.filter((existingItem) => existingItem !== itemToRemove);
        setRcUnitItemsAndNotify(newItems);
    }

    const handleSeparatorChange = (index, separator) => {
        const changedPart = {...rcUnitItems[index], separator: separator};
        const newItems = [...rcUnitItems.slice(0, index), changedPart, ...rcUnitItems.slice(index + 1)];
        setRcUnitItemsAndNotify(newItems);
    }

    const setRcUnitItemsAndNotify = (items) => {
        setRcUnitItems(items);
        onChange(items);
    }

    return <div className='promoters' style={styles.rcUnitItems}>
        {rcUnitItems.map((part, index) => (
            <React.Fragment key={index}>
                <span>{part.value}</span>
                <a href='#' onClick={() => handleItemRemoved(part)}>
                    <i className='fa fa-trash' aria-hidden='true'/>
                    {/*&#10060;*/}
                </a>
                <select
                    className='promoter-separator'
                    value={part.separator}
                    onChange={e => handleSeparatorChange(index, e.target.value)}
                >
                    <option>-</option>
                    <option>,</option>
                    <option>.</option>
                    <option/>
                </select>
            </React.Fragment>
        ))}
        <div className='promoter-autocomplete'>
            <ConstructMarkerAutocomplete
                publicationId={publicationId}
                onSelect={handleItemSelected}
                onChangeWithObject={handleAutoCompleteChange}
            />
        </div>
    </div>;
}

export default ConstructRegulatoryCodingUnitList;