import * as React from 'react';
import {
    and,
    ControlProps,
    isControl,
    JsonFormsRendererRegistryEntry,
    optionIs,
    rankWith,
} from '@jsonforms/core';
import { withJsonFormsControlProps } from '@jsonforms/react';

const STANDARD_BACKGROUNDS = ['AB', 'TU', 'WIK', 'AB/TU', 'unknown'];
const OTHER_SENTINEL = '__other';

/**
 * Background select with revealed "Other" text input. "Other selected" is
 * tracked as local component state, distinct from the form value, so picking
 * Other on an empty field doesn't immediately hide the input again.
 */
function BackgroundSelectWithOtherRenderer({ data, handleChange, path, label }: ControlProps) {
    const fieldName = path.split('.').pop() ?? path;
    const inputId = `fr-${fieldName}`;
    const labelId = `fr-label-${fieldName}`;
    const value = (data as string | undefined) ?? '';
    const isStandard = STANDARD_BACKGROUNDS.includes(value);
    const [otherSelected, setOtherSelected] = React.useState(() => value !== '' && !isStandard);
    const selectValue = isStandard ? value : (otherSelected ? OTHER_SENTINEL : '');

    return (
        <tr>
            <th className='w-25' scope='row' id={labelId}>
                <label htmlFor={inputId} className='mb-0'>{label}</label>
            </th>
            <td>
                <div className='d-flex' style={{ gap: 8 }}>
                    <select
                        id={inputId}
                        className='form-control'
                        style={{ maxWidth: 200 }}
                        value={selectValue}
                        onChange={(e) => {
                            const v = e.target.value;
                            if (v === OTHER_SENTINEL) {
                                setOtherSelected(true);
                                if (isStandard) {handleChange(path, '');}
                            } else {
                                setOtherSelected(false);
                                handleChange(path, v);
                            }
                        }}
                    >
                        <option value=''>(select)</option>
                        {STANDARD_BACKGROUNDS.map((s) => (
                            <option key={s} value={s}>{s}</option>
                        ))}
                        <option value={OTHER_SENTINEL}>Other</option>
                    </select>
                    {otherSelected && (
                        <input
                            type='text'
                            id={`${inputId}-other`}
                            className='form-control'
                            placeholder='Other'
                            aria-label={`${label} (other)`}
                            value={value}
                            onChange={(e) => handleChange(path, e.target.value)}
                        />
                    )}
                </div>
            </td>
        </tr>
    );
}

export const backgroundSelectWithOtherRendererEntry: JsonFormsRendererRegistryEntry = {
    tester: rankWith(20, and(isControl, optionIs('widget', 'backgroundSelectWithOther'))),
    renderer: withJsonFormsControlProps(BackgroundSelectWithOtherRenderer),
};
