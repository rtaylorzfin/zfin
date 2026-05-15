import * as React from 'react';
import {
    GroupLayout,
    JsonFormsRendererRegistryEntry,
    LayoutProps,
    rankWith,
    uiTypeIs,
} from '@jsonforms/core';
import { ResolvedJsonFormsDispatch, withJsonFormsLayoutProps } from '@jsonforms/react';

/**
 * Renders a uiSchema "Group" element as a ZFIN-styled section: section.section
 * with an h2.heading and a table.table-borderless body. Child elements are
 * dispatched into the table body via ResolvedJsonFormsDispatch — the row
 * renderer is responsible for producing <tr> wrappers.
 */
function SectionRenderer({
    uischema,
    schema,
    path,
    enabled,
    renderers,
    cells,
}: LayoutProps) {
    const layout = uischema as GroupLayout;
    const label = layout.label ?? schema?.title ?? '';
    const sectionId = label
        ? label.toLowerCase().replace(/[^a-z0-9-_:.]/g, '-').replace(/-+/g, '-')
        : 'section';
    const headingId = `${sectionId}-heading`;

    return (
        <section className='section' id={sectionId} aria-labelledby={headingId}>
            <h2 id={headingId} className='heading'>{label}</h2>
            <table className='table table-borderless'>
                <tbody>
                    {(layout.elements ?? []).map((child, index) => (
                        <ResolvedJsonFormsDispatch
                            key={`${path}-${index}`}
                            uischema={child}
                            schema={schema}
                            path={path}
                            enabled={enabled}
                            renderers={renderers}
                            cells={cells}
                        />
                    ))}
                </tbody>
            </table>
        </section>
    );
}

export const sectionRendererEntry: JsonFormsRendererRegistryEntry = {
    tester: rankWith(10, uiTypeIs('Group')),
    renderer: withJsonFormsLayoutProps(SectionRenderer),
};
