import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { overviewSchema, OverviewFormValues } from '../schemas/overview';
import { useCreateLineSubmission, useUpdateOverview } from '../api/queries';
import { LineSubmissionResponse } from '../api/types';

type Props = {
    submission: LineSubmissionResponse | null;
    onCreated: (s: LineSubmissionResponse) => void;
};

type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';

const AUTOSAVE_DEBOUNCE_MS = 800;

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

    const create = useCreateLineSubmission();
    const updateOverview = useUpdateOverview();

    const [status, setStatus] = React.useState<SaveStatus>('idle');
    const [errorMessage, setErrorMessage] = React.useState<string | null>(null);

    // Mutable ID: starts null for a new submission, gets a value after first save.
    const submissionIdRef = React.useRef<string | null>(submission?.zdbID ?? null);

    const values = watch();

    React.useEffect(() => {
        // Skip autosave until the user has actually interacted. isDirty stays false
        // through the initial render + the post-Zod re-render that flips isValid.
        if (!formState.isDirty) {return;}
        if (!formState.isValid) {
            setStatus('error');
            setErrorMessage('Some fields exceed their length limit.');
            return;
        }

        const handle = window.setTimeout(async () => {
            setStatus('saving');
            setErrorMessage(null);
            try {
                let id = submissionIdRef.current;
                if (!id) {
                    const created = await create.mutateAsync();
                    submissionIdRef.current = created.zdbID;
                    id = created.zdbID;
                    onCreated(created);
                    window.history.replaceState(
                        {},
                        '',
                        `/action/zirc/line-submission/${id}/edit`
                    );
                }
                await updateOverview.mutateAsync({ id, update: values });
                setStatus('saved');
            } catch (e: unknown) {
                setStatus('error');
                setErrorMessage(e instanceof Error ? e.message : 'Save failed');
            }
        }, AUTOSAVE_DEBOUNCE_MS);

        return () => window.clearTimeout(handle);
    }, [
        values.name,
        values.abbreviation,
        values.previousNames,
        formState.isDirty,
        formState.isValid,
    ]);

    return (
        <section id='Overview' className='zirc-section mb-4'>
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

function SaveStatusBadge({
    status,
    message,
}: {
    status: SaveStatus;
    message: string | null;
}) {
    if (status === 'idle') {return null;}
    if (status === 'saving') {return <span className='text-muted small'>Saving…</span>;}
    if (status === 'saved') {return <span className='text-success small'>Saved</span>;}
    return (
        <span className='text-danger small' title={message ?? ''}>
            Error{message ? `: ${message}` : ''}
        </span>
    );
}
