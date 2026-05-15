import * as React from 'react';
import { FieldTemplateProps } from '@rjsf/utils';

/**
 * Renders one leaf field as a table row matching the reference markup:
 *   <tr>
 *     <th class="w-25" scope="row" id="fr-label-X"><label for="fr-X">…</label></th>
 *     <td>{widget}{errors}</td>
 *   </tr>
 *
 * Object fields are passed through unchanged (their wrapping is handled by
 * the ObjectFieldTemplate, which produces the section / table around them).
 */
export function RowFieldTemplate(props: FieldTemplateProps) {
    const { id, label, displayLabel, children, rawErrors, schema } = props;

    // Object fields skip this template entirely — the SectionObjectFieldTemplate
    // is responsible for object-level structure.
    if (schema.type === 'object') {
        return <>{children}</>;
    }

    if (!displayLabel) {
        return (
            <tr>
                <td colSpan={2}>{children}</td>
            </tr>
        );
    }

    // id is e.g. "fr-name"; label id mirrors the reference pattern.
    const fieldName = id.replace(/^fr-?/, '');
    const labelId = `fr-label-${fieldName}`;

    return (
        <tr>
            <th className='w-25' scope='row' id={labelId}>
                <label htmlFor={id} className='mb-0'>{label}</label>
            </th>
            <td>
                {children}
                {rawErrors && rawErrors.length > 0 && (
                    <small className='text-danger'>{rawErrors[0]}</small>
                )}
            </td>
        </tr>
    );
}
