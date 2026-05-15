import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
    additionalInfoSchema,
    AdditionalInfoFormValues,
} from '../schemas/additionalInfo';
import { useUpdateAdditionalInfo } from '../api/queries';
import { LineSubmissionResponse } from '../api/types';
import { useSectionAutosave } from '../hooks/useSectionAutosave';
import { SaveStatusBadge } from '../components/SaveStatusBadge';

export function AdditionalInfoForm({ submission }: { submission: LineSubmissionResponse }) {
    const { register, watch, formState } = useForm<AdditionalInfoFormValues>({
        resolver: zodResolver(additionalInfoSchema),
        defaultValues: {
            unreportedFeaturesDetails: submission.unreportedFeaturesDetails ?? '',
            husbandryInfo: submission.husbandryInfo ?? '',
            additionalInfo: submission.additionalInfo ?? '',
        },
        mode: 'onChange',
    });
    const values = watch();
    const updateInfo = useUpdateAdditionalInfo();

    const { status, errorMessage } = useSectionAutosave({
        values,
        isDirty: formState.isDirty,
        isValid: formState.isValid,
        save: (v) =>
            updateInfo.mutateAsync({
                id: submission.zdbID,
                update: {
                    unreportedFeaturesDetails: v.unreportedFeaturesDetails || null,
                    husbandryInfo: v.husbandryInfo || null,
                    additionalInfo: v.additionalInfo || null,
                },
            }),
    });

    return (
        <section id='additional-info' className='zirc-section mb-4'>
            <header className='d-flex justify-content-between align-items-center mb-2'>
                <h2 className='h4 mb-0'>Additional Info</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </header>

            <div className='form-group'>
                <label htmlFor='zirc-additional-unreported'>
                    Unreported features details
                </label>
                <textarea
                    id='zirc-additional-unreported'
                    className='form-control'
                    rows={3}
                    {...register('unreportedFeaturesDetails')}
                />
                {formState.errors.unreportedFeaturesDetails && (
                    <small className='text-danger'>
                        {formState.errors.unreportedFeaturesDetails.message}
                    </small>
                )}
            </div>

            <div className='form-group'>
                <label htmlFor='zirc-additional-husbandry'>Husbandry info</label>
                <textarea
                    id='zirc-additional-husbandry'
                    className='form-control'
                    rows={3}
                    {...register('husbandryInfo')}
                />
                {formState.errors.husbandryInfo && (
                    <small className='text-danger'>
                        {formState.errors.husbandryInfo.message}
                    </small>
                )}
            </div>

            <div className='form-group'>
                <label htmlFor='zirc-additional-info'>Additional info</label>
                <textarea
                    id='zirc-additional-info'
                    className='form-control'
                    rows={3}
                    {...register('additionalInfo')}
                />
                {formState.errors.additionalInfo && (
                    <small className='text-danger'>
                        {formState.errors.additionalInfo.message}
                    </small>
                )}
            </div>
        </section>
    );
}
