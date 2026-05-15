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
        <section
            className='section'
            id='acceptance-reasons'
            aria-labelledby='acceptance-reasons-heading'
        >
            <div className='d-flex justify-content-between align-items-center'>
                <h2 id='acceptance-reasons-heading' className='heading'>Acceptance Reasons</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <table className='table table-borderless'>
                <tbody>
                    <tr>
                        <th className='w-25' scope='row' id='fr-label-reasons'>
                            <label htmlFor='fr-reasons' className='mb-0'>
                                Why ZIRC should accept this line
                            </label>
                        </th>
                        <td>
                            <fieldset
                                className='border-0 p-0 m-0'
                                aria-labelledby='fr-label-reasons'
                            >
                                {CANONICAL_REASONS.map((r) => (
                                    <div key={r.value} className='form-check'>
                                        <input
                                            id={`fr-reasons-${r.value}`}
                                            type='checkbox'
                                            className='form-check-input'
                                            value={r.value}
                                            {...register('reasons')}
                                        />
                                        <label
                                            className='form-check-label'
                                            htmlFor={`fr-reasons-${r.value}`}
                                        >
                                            {r.label}
                                        </label>
                                    </div>
                                ))}
                                {showOther && (
                                    <div className='mt-2 ml-4' style={{ maxWidth: 600 }}>
                                        <label
                                            htmlFor='fr-reasons-other-text'
                                            className='sr-only'
                                        >
                                            Why ZIRC should accept this line (other details)
                                        </label>
                                        <input
                                            id='fr-reasons-other-text'
                                            type='text'
                                            className='form-control'
                                            placeholder='Describe'
                                            {...register('reasonsOther')}
                                        />
                                        {formState.errors.reasonsOther && (
                                            <small className='text-danger'>
                                                {formState.errors.reasonsOther.message}
                                            </small>
                                        )}
                                    </div>
                                )}
                            </fieldset>
                        </td>
                    </tr>
                </tbody>
            </table>
        </section>
    );
}
