import * as React from 'react';
import { useForm, UseFormRegister } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
    backgroundSchema,
    BackgroundFormValues,
    fromFormBool,
    toFormBool,
    TriBool,
} from '../schemas/background';
import { useUpdateBackground } from '../api/queries';
import { LineSubmissionResponse } from '../api/types';
import { useSectionAutosave } from '../hooks/useSectionAutosave';
import { SaveStatusBadge } from '../components/SaveStatusBadge';

const TRI_OPTIONS: ReadonlyArray<{ value: TriBool; label: string }> = [
    { value: 'true', label: 'Yes' },
    { value: 'false', label: 'No' },
    { value: '', label: 'Unspecified' },
];

function TriStateRadioGroup({
    name,
    label,
    idPrefix,
    register,
}: {
    name: 'singleAllelic' | 'backgroundChangeable';
    label: string;
    idPrefix: string;
    register: UseFormRegister<BackgroundFormValues>;
}) {
    return (
        <fieldset className='form-group'>
            <legend className='col-form-label h6'>{label}</legend>
            {TRI_OPTIONS.map((opt, i) => (
                <div key={opt.label} className='form-check form-check-inline'>
                    <input
                        id={`${idPrefix}-${i}`}
                        type='radio'
                        className='form-check-input'
                        value={opt.value}
                        {...register(name)}
                    />
                    <label className='form-check-label' htmlFor={`${idPrefix}-${i}`}>
                        {opt.label}
                    </label>
                </div>
            ))}
        </fieldset>
    );
}

export function BackgroundForm({ submission }: { submission: LineSubmissionResponse }) {
    const { register, watch, formState } = useForm<BackgroundFormValues>({
        resolver: zodResolver(backgroundSchema),
        defaultValues: {
            singleAllelic: toFormBool(submission.singleAllelic),
            maternalBackground: submission.maternalBackground ?? '',
            paternalBackground: submission.paternalBackground ?? '',
            backgroundChangeable: toFormBool(submission.backgroundChangeable),
            backgroundChangeConcerns: submission.backgroundChangeConcerns ?? '',
        },
        mode: 'onChange',
    });
    const values = watch();
    const updateBackground = useUpdateBackground();

    const { status, errorMessage } = useSectionAutosave({
        values,
        isDirty: formState.isDirty,
        isValid: formState.isValid,
        save: (v) =>
            updateBackground.mutateAsync({
                id: submission.zdbID,
                update: {
                    singleAllelic: fromFormBool(v.singleAllelic),
                    maternalBackground: v.maternalBackground || null,
                    paternalBackground: v.paternalBackground || null,
                    backgroundChangeable: fromFormBool(v.backgroundChangeable),
                    backgroundChangeConcerns: v.backgroundChangeConcerns || null,
                },
            }),
    });

    return (
        <section id='background' className='zirc-section mb-4'>
            <header className='d-flex justify-content-between align-items-center mb-2'>
                <h2 className='h4 mb-0'>Background</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </header>

            <TriStateRadioGroup
                name='singleAllelic'
                label='Single allelic?'
                idPrefix='zirc-background-single-allelic'
                register={register}
            />

            <div className='form-group'>
                <label htmlFor='zirc-background-maternal'>Maternal background</label>
                <input
                    id='zirc-background-maternal'
                    className='form-control'
                    autoComplete='off'
                    {...register('maternalBackground')}
                />
                {formState.errors.maternalBackground && (
                    <small className='text-danger'>
                        {formState.errors.maternalBackground.message}
                    </small>
                )}
            </div>

            <div className='form-group'>
                <label htmlFor='zirc-background-paternal'>Paternal background</label>
                <input
                    id='zirc-background-paternal'
                    className='form-control'
                    autoComplete='off'
                    {...register('paternalBackground')}
                />
                {formState.errors.paternalBackground && (
                    <small className='text-danger'>
                        {formState.errors.paternalBackground.message}
                    </small>
                )}
            </div>

            <TriStateRadioGroup
                name='backgroundChangeable'
                label='Is the genetic background changeable?'
                idPrefix='zirc-background-changeable'
                register={register}
            />

            <div className='form-group'>
                <label htmlFor='zirc-background-concerns'>
                    Background-change concerns
                </label>
                <textarea
                    id='zirc-background-concerns'
                    className='form-control'
                    rows={3}
                    {...register('backgroundChangeConcerns')}
                />
                {formState.errors.backgroundChangeConcerns && (
                    <small className='text-danger'>
                        {formState.errors.backgroundChangeConcerns.message}
                    </small>
                )}
            </div>
        </section>
    );
}
