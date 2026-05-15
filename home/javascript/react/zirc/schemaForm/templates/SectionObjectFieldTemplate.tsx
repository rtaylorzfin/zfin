import * as React from 'react';
import { ObjectFieldTemplateProps } from '@rjsf/utils';

/**
 * Wraps a JSON-Schema object as a ZFIN-styled section: section.section with an
 * h2.heading and a table.table-borderless body whose rows are the field
 * outputs.
 *
 * Render rules:
 *  - When the object has no title, render only its children (this is how the
 *    top-level form root behaves once the schema covers more than one section).
 *  - When the object has a title, render the section wrapper. Section id is
 *    derived from the schema's id-path (rjsf idSchema) for nested objects,
 *    or from the title for a single-section schema where the title is on the
 *    root.
 */
export function SectionObjectFieldTemplate(props: ObjectFieldTemplateProps) {
    const { title, idSchema, properties } = props;

    if (!title) {
        return (
            <>
                {properties.map((p) => (
                    <React.Fragment key={p.name}>{p.content}</React.Fragment>
                ))}
            </>
        );
    }

    const sectionId = deriveSectionId(idSchema?.$id, title);
    const headingId = `${sectionId}-heading`;

    return (
        <section className='section' id={sectionId} aria-labelledby={headingId}>
            <h2 id={headingId} className='heading'>{title}</h2>
            <table className='table table-borderless'>
                <tbody>
                    {properties.map((p) => (
                        <React.Fragment key={p.name}>{p.content}</React.Fragment>
                    ))}
                </tbody>
            </table>
        </section>
    );
}

const ID_PREFIX_RE = /^fr-?/;

function deriveSectionId(rjsfId: string | undefined, title: string): string {
    // For nested objects rjsf produces ids like "fr-acceptance" (we set the
    // root idPrefix to "fr"). Strip the prefix to get the section id.
    if (rjsfId && rjsfId !== 'fr') {
        return rjsfId.replace(ID_PREFIX_RE, '');
    }
    // Root-level single-section schema (the current spike state for Overview):
    // derive from the title via the same algorithm zfn:makeDomIdentifier uses
    // on the JSP side, so the dataPage side-nav anchors line up.
    return title.toLowerCase().replace(/[^a-z0-9-_:.]/g, '-').replace(/-+/g, '-');
}
