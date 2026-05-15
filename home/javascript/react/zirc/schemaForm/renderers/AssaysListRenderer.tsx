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
import { AssaySummary } from '../../api/types';
import { useAddAssay, useDeleteAssay } from '../../api/queries';

/**
 * Renders the per-mutation list of genotyping assays as a stack of cards.
 *
 * Each card collapses to a one-line summary (assay type + sort order). The
 * full per-assay field set lands in M4.2, when expanding a card will mount
 * a per-assay schema-driven editor that PATCHes /assays/{id} with flat
 * paths — flat paths are why each assay gets its own card-scoped form
 * instead of inheriting the mutation's path namespace.
 *
 * Add/Delete go through dedicated endpoints (see useAddAssay /
 * useDeleteAssay); MutationEdit's diff filter skips /assays so these
 * mutations don't fight the autosave diff.
 *
 * The mutation id needed for the endpoints comes through JsonForms'
 * `config` prop, which MutationEdit threads through.
 */
function AssaysListRenderer({ data, config }: ControlProps) {
    const assays = (data as AssaySummary[] | undefined) ?? [];
    const mutationId = (config as { mutationId?: number } | undefined)?.mutationId;
    const addAssay = useAddAssay();
    const deleteAssay = useDeleteAssay();

    const handleAdd = () => {
        if (!mutationId) {return;}
        addAssay.mutate(mutationId);
    };

    const handleDelete = (assayId: number) => {
        if (!mutationId) {return;}
        // eslint-disable-next-line no-alert
        if (!window.confirm('Delete this genotyping assay? This action cannot be undone.')) {return;}
        deleteAssay.mutate({ mutationId, assayId });
    };

    if (assays.length === 0) {
        return (
            <div>
                <p className='text-muted'>No genotyping assays recorded for this mutation.</p>
                <button
                    type='button'
                    className='btn btn-sm btn-outline-secondary'
                    onClick={handleAdd}
                    disabled={!mutationId || addAssay.isPending}
                >
                    + Add assay
                </button>
            </div>
        );
    }

    return (
        <div>
            <ul className='list-unstyled'>
                {assays.map((a) => (
                    <li key={a.id} className='border rounded p-2 mb-2 d-flex justify-content-between align-items-center'>
                        <div>
                            <strong>{a.assayType || `Assay #${a.sortOrder}`}</strong>
                        </div>
                        <div>
                            <button
                                type='button'
                                className='btn btn-sm btn-outline-danger'
                                onClick={() => handleDelete(a.id)}
                                disabled={deleteAssay.isPending}
                            >
                                Delete
                            </button>
                        </div>
                    </li>
                ))}
            </ul>
            <button
                type='button'
                className='btn btn-sm btn-outline-secondary'
                onClick={handleAdd}
                disabled={!mutationId || addAssay.isPending}
            >
                + Add assay
            </button>
        </div>
    );
}

export const assaysListRendererEntry: JsonFormsRendererRegistryEntry = {
    tester: rankWith(20, and(isControl, optionIs('widget', 'assaysList'))),
    renderer: withJsonFormsControlProps(AssaysListRenderer),
};
