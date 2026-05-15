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
            abbreviation: submission?.abbreviation ?? '',
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
        <section id='overview' className='zirc-section mb-4'>
            <header className='d-flex justify-content-between align-items-center mb-2'>
                <h2 className='h4 mb-0'>Overview</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </header>

            <div className='form-group'>
                <label htmlFor='zirc-overview-name'>Name</label>
                <input
                    id='zirc-overview-name'
                    className='form-control'
                    autoComplete='off'
                    {...register('name')}
                />
                {formState.errors.name && (
                    <small className='text-danger'>{formState.errors.name.message}</small>
                )}
            </div>

            <div className='form-group'>
                <label htmlFor='zirc-overview-abbreviation'>Abbreviation</label>
                <input
                    id='zirc-overview-abbreviation'
                    className='form-control'
                    autoComplete='off'
                    {...register('abbreviation')}
                />
                {formState.errors.abbreviation && (
                    <small className='text-danger'>
                        {formState.errors.abbreviation.message}
                    </small>
                )}
            </div>

            <div className='form-group'>
                <label htmlFor='zirc-overview-previous-names'>Previous names</label>
                <input
                    id='zirc-overview-previous-names'
                    className='form-control'
                    autoComplete='off'
                    {...register('previousNames')}
                />
                {formState.errors.previousNames && (
                    <small className='text-danger'>
                        {formState.errors.previousNames.message}
                    </small>
                )}
            </div>
        </section>
    );
}
