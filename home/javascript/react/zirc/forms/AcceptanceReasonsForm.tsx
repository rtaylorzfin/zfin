import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
    acceptanceReasonsSchema,
    AcceptanceReasonsFormValues,
    CANONICAL_REASONS,
    REASON_OTHER_VALUE,
} from '../schemas/acceptanceReasons';
import { useUpdateAcceptanceReasons } from '../api/queries';
import { LineSubmissionResponse } from '../api/types';
import { useSectionAutosave } from '../hooks/useSectionAutosave';
import { SaveStatusBadge } from '../components/SaveStatusBadge';

export function AcceptanceReasonsForm({ submission }: { submission: LineSubmissionResponse }) {
    const { register, watch, formState } = useForm<AcceptanceReasonsFormValues>({
        resolver: zodResolver(acceptanceReasonsSchema),
        defaultValues: {
            reasons: submission.reasons,
            reasonsOther: submission.reasonsOther ?? '',
        },
        mode: 'onChange',
    });
    const values = watch();
    const updateReasons = useUpdateAcceptanceReasons();

    const showOther = values.reasons.includes(REASON_OTHER_VALUE);

    const { status, errorMessage } = useSectionAutosave({
        values,
        isDirty: formState.isDirty,
        isValid: formState.isValid,
        save: (v) =>
            updateReasons.mutateAsync({
                id: submission.zdbID,
                update: {
                    reasons: v.reasons,
                    reasonsOther: v.reasons.includes(REASON_OTHER_VALUE)
                        ? (v.reasonsOther || null)
                        : null,
                },
            }),
    });

    return (
        <section id='acceptance-reasons' className='zirc-section mb-4'>
            <header className='d-flex justify-content-between align-items-center mb-2'>
                <h2 className='h4 mb-0'>Acceptance Reasons</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </header>
            <fieldset>
                {CANONICAL_REASONS.map((r) => (
                    <div key={r.value} className='form-check'>
                        <input
                            id={`zirc-reasons-${r.value}`}
                            type='checkbox'
                            className='form-check-input'
                            value={r.value}
                            {...register('reasons')}
                        />
                        <label
                            htmlFor={`zirc-reasons-${r.value}`}
                            className='form-check-label'
                        >
                            {r.label}
                        </label>
                    </div>
                ))}
            </fieldset>
            {showOther && (
                <div className='form-group mt-2'>
                    <label htmlFor='zirc-reasons-other'>Describe the &quot;Other&quot; reason</label>
                    <textarea
                        id='zirc-reasons-other'
                        className='form-control'
                        rows={3}
                        {...register('reasonsOther')}
                    />
                    {formState.errors.reasonsOther && (
                        <small className='text-danger'>
                            {formState.errors.reasonsOther.message}
                        </small>
                    )}
                </div>
            )}
        </section>
    );
}
