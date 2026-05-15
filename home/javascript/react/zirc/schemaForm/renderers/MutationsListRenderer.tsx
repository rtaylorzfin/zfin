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
import { MutationResponse } from '../../api/types';
import { useAddMutation, useDeleteMutation } from '../../api/queries';

/**
 * Renders the read-only summary of mutations on the submission page.
 *
 * Each row is a card with the mutation's summary fields + Edit and Delete
 * actions. Add/Delete go through dedicated REST endpoints (not the field-
 * path PATCH), so SchemaForm filters /mutations out of its leaf diff —
 * changes here flow through the React Query cache invalidation triggered
 * by the mutations.
 *
 * The submission id needed for the endpoints comes through JsonForms'
 * `config` prop, which SchemaForm threads through.
 */
function MutationsListRenderer({ data, config }: ControlProps) {
    const mutations = (data as MutationResponse[] | undefined) ?? [];
    const submissionId = (config as { submissionId?: string } | undefined)?.submissionId;
    const addMutation = useAddMutation();
    const deleteMutation = useDeleteMutation();

    const handleAdd = () => {
        if (!submissionId) {return;}
        addMutation.mutate(submissionId);
    };

    const handleDelete = (mutationId: number) => {
        if (!submissionId) {return;}
        // eslint-disable-next-line no-alert
        if (!window.confirm('Delete this mutation? This action cannot be undone.')) {return;}
        deleteMutation.mutate({ submissionId, mutationId });
    };

    if (mutations.length === 0) {
        return (
            <div>
                <p className='text-muted'>No mutations recorded for this submission.</p>
                <button
                    type='button'
                    className='btn btn-sm btn-outline-secondary'
                    onClick={handleAdd}
                    disabled={!submissionId || addMutation.isPending}
                >
                    + Add mutation
                </button>
            </div>
        );
    }

    return (
        <div>
            <ul className='list-unstyled'>
                {mutations.map((m) => (
                    <li key={m.id} className='border rounded p-2 mb-2 d-flex justify-content-between align-items-center'>
                        <div>
                            <strong>{m.alleleDesignation || `Mutation #${m.sortOrder}`}</strong>
                            {m.mutationType && (
                                <span className='text-muted small ml-2'>{m.mutationType}</span>
                            )}
                        </div>
                        <div>
                            <a
                                className='btn btn-sm btn-outline-secondary mr-1'
                                href={`/action/zirc/mutation/${m.id}/edit`}
                            >
                                Edit
                            </a>
                            <button
                                type='button'
                                className='btn btn-sm btn-outline-danger'
                                onClick={() => handleDelete(m.id)}
                                disabled={deleteMutation.isPending}
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
                disabled={!submissionId || addMutation.isPending}
            >
                + Add mutation
            </button>
        </div>
    );
}

export const mutationsListRendererEntry: JsonFormsRendererRegistryEntry = {
    tester: rankWith(20, and(isControl, optionIs('widget', 'mutationsList'))),
    renderer: withJsonFormsControlProps(MutationsListRenderer),
};
