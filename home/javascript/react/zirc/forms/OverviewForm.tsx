import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { overviewSchema, OverviewFormValues } from '../schemas/overview';
import { useCreateLineSubmission, useUpdateOverview } from '../api/queries';
import { LineSubmissionResponse } from '../api/types';
import { useSectionAutosave } from '../hooks/useSectionAutosave';
import { SaveStatusBadge } from '../components/SaveStatusBadge';

type Props = {
    submission: LineSubmissionResponse | null;
    onCreated: (s: LineSubmissionResponse) => void;
};

export function OverviewForm({ submission, onCreated }: Props) {
    const { register, watch, formState } = useForm<OverviewFormValues>({
        resolver: zodResolver(overviewSchema),
        defaultValues: {
            name: submission?.name ?? '',
            previousNames: submission?.previousNames ?? '',
        },
        mode: 'onChange',
    });
    const values = watch();
    const create = useCreateLineSubmission();
    const updateOverview = useUpdateOverview();

    // Mutable ID: null for a new submission, set after the first save creates the draft.
    const submissionIdRef = React.useRef<string | null>(submission?.zdbID ?? null);

    const { status, errorMessage } = useSectionAutosave({
        values,
        isDirty: formState.isDirty,
        isValid: formState.isValid,
        save: async (v) => {
            let id = submissionIdRef.current;
            if (!id) {
                const created = await create.mutateAsync();
                submissionIdRef.current = created.zdbID;
                id = created.zdbID;
                onCreated(created);
                window.history.replaceState(
                    {},
                    '',
                    `/action/zirc/line-submission/${id}/edit`,
                );
            }
            await updateOverview.mutateAsync({ id, update: v });
        },
    });

    return (
        <section className='section' id='overview' aria-labelledby='overview-heading'>
            <div className='d-flex justify-content-between align-items-center'>
                <h2 id='overview-heading' className='heading'>Overview</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <table className='table table-borderless'>
                <tbody>
                    <tr>
                        <th className='w-25' scope='row' id='fr-label-zdbID'>ID</th>
                        <td>
                            {submission?.zdbID
                                ? <code>{submission.zdbID}</code>
                                : <span className='text-muted small'>(assigned on first save)</span>}
                        </td>
                    </tr>
                    <tr>
                        <th className='w-25' scope='row' id='fr-label-name'>
                            <label htmlFor='fr-name' className='mb-0'>Name</label>
                        </th>
                        <td>
                            <input
                                id='fr-name'
                                type='text'
                                className='form-control'
                                autoComplete='off'
                                {...register('name')}
                            />
                            {formState.errors.name && (
                                <small className='text-danger'>{formState.errors.name.message}</small>
                            )}
                        </td>
                    </tr>
                    <tr>
                        <th className='w-25' scope='row' id='fr-label-previousNames'>
                            <label htmlFor='fr-previousNames' className='mb-0'>Previous Names</label>
                        </th>
                        <td>
                            <input
                                id='fr-previousNames'
                                type='text'
                                className='form-control'
                                autoComplete='off'
                                {...register('previousNames')}
                            />
                            {formState.errors.previousNames && (
                                <small className='text-danger'>{formState.errors.previousNames.message}</small>
                            )}
                        </td>
                    </tr>
                </tbody>
            </table>
        </section>
    );
}
