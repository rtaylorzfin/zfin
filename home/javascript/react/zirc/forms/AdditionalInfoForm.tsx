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
        <section
            className='section'
            id='additional-info'
            aria-labelledby='additional-info-heading'
        >
            <div className='d-flex justify-content-between align-items-center'>
                <h2 id='additional-info-heading' className='heading'>Additional Info</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <table className='table table-borderless'>
                <tbody>
                    <tr>
                        <th className='w-25' scope='row' id='fr-label-unreportedFeaturesDetails'>
                            <label htmlFor='fr-unreportedFeaturesDetails' className='mb-0'>
                                Unreported Features Details
                            </label>
                        </th>
                        <td>
                            <textarea
                                id='fr-unreportedFeaturesDetails'
                                className='form-control'
                                rows={3}
                                {...register('unreportedFeaturesDetails')}
                            />
                            {formState.errors.unreportedFeaturesDetails && (
                                <small className='text-danger'>
                                    {formState.errors.unreportedFeaturesDetails.message}
                                </small>
                            )}
                        </td>
                    </tr>
                    <tr>
                        <th className='w-25' scope='row' id='fr-label-husbandryInfo'>
                            <label htmlFor='fr-husbandryInfo' className='mb-0'>Husbandry Info</label>
                        </th>
                        <td>
                            <textarea
                                id='fr-husbandryInfo'
                                className='form-control'
                                rows={3}
                                placeholder='Husbandry-specific information, e.g. special feeding regime'
                                {...register('husbandryInfo')}
                            />
                            {formState.errors.husbandryInfo && (
                                <small className='text-danger'>
                                    {formState.errors.husbandryInfo.message}
                                </small>
                            )}
                        </td>
                    </tr>
                    <tr>
                        <th className='w-25' scope='row' id='fr-label-additionalInfo'>
                            <label htmlFor='fr-additionalInfo' className='mb-0'>Additional Info</label>
                        </th>
                        <td>
                            <textarea
                                id='fr-additionalInfo'
                                className='form-control'
                                rows={3}
                                {...register('additionalInfo')}
                            />
                            {formState.errors.additionalInfo && (
                                <small className='text-danger'>
                                    {formState.errors.additionalInfo.message}
                                </small>
                            )}
                        </td>
                    </tr>
                </tbody>
            </table>
        </section>
    );
}
