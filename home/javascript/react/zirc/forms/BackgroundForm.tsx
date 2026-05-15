import * as React from 'react';
import { useForm, UseFormRegister } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
    backgroundSchema,
    BackgroundFormValues,
    fromFormBool,
    STANDARD_BACKGROUNDS,
    toFormBool,
} from '../schemas/background';
import { useUpdateBackground } from '../api/queries';
import { LineSubmissionResponse } from '../api/types';
import { useSectionAutosave } from '../hooks/useSectionAutosave';
import { SaveStatusBadge } from '../components/SaveStatusBadge';

const OTHER_SENTINEL = '__other';

function YesNoRadio({
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
        <tr>
            <th className='w-25' scope='row' id={`fr-label-${name}`}>{label}</th>
            <td>
                <div role='radiogroup' aria-labelledby={`fr-label-${name}`}>
                    <div className='form-check form-check-inline'>
                        <input
                            type='radio'
                            id={`${idPrefix}-true`}
                            className='form-check-input'
                            value='true'
                            {...register(name)}
                        />
                        <label className='form-check-label' htmlFor={`${idPrefix}-true`}>Yes</label>
                    </div>
                    <div className='form-check form-check-inline'>
                        <input
                            type='radio'
                            id={`${idPrefix}-false`}
                            className='form-check-input'
                            value='false'
                            {...register(name)}
                        />
                        <label className='form-check-label' htmlFor={`${idPrefix}-false`}>No</label>
                    </div>
                </div>
            </td>
        </tr>
    );
}

function BackgroundSelectRow({
    name,
    label,
    value,
    onChange,
}: {
    name: 'maternalBackground' | 'paternalBackground';
    label: string;
    value: string;
    onChange: (v: string) => void;
}) {
    const idBase = `fr-${name}`;
    const isStandard = STANDARD_BACKGROUNDS.includes(value);
    // "Other selected" is local UI state, distinct from the form value: picking
    // Other initially clears the value so the user can type, and we must keep
    // showing the input + the Other-sentinel in the select while they do.
    const [otherSelected, setOtherSelected] = React.useState(() => value !== '' && !isStandard);
    const selectValue = isStandard ? value : (otherSelected ? OTHER_SENTINEL : '');

    return (
        <tr>
            <th className='w-25' scope='row' id={`fr-label-${name}`}>
                <label htmlFor={idBase} className='mb-0'>{label}</label>
            </th>
            <td>
                <div className='d-flex' style={{ gap: 8 }}>
                    <select
                        id={idBase}
                        className='form-control'
                        style={{ maxWidth: 200 }}
                        value={selectValue}
                        onChange={(e) => {
                            const v = e.target.value;
                            if (v === OTHER_SENTINEL) {
                                setOtherSelected(true);
                                // If the current value happens to be a standard one,
                                // clear it so the revealed input starts empty.
                                if (isStandard) {onChange('');}
                            } else {
                                setOtherSelected(false);
                                onChange(v);
                            }
                        }}
                    >
                        <option value=''>(select)</option>
                        {STANDARD_BACKGROUNDS.map((s) => (
                            <option key={s} value={s}>{s}</option>
                        ))}
                        <option value={OTHER_SENTINEL}>Other</option>
                    </select>
                    {otherSelected && (
                        <input
                            type='text'
                            id={`${idBase}-other`}
                            className='form-control'
                            placeholder='Other'
                            aria-label={`${label} (other)`}
                            value={value}
                            onChange={(e) => onChange(e.target.value)}
                        />
                    )}
                </div>
            </td>
        </tr>
    );
}

export function BackgroundForm({ submission }: { submission: LineSubmissionResponse }) {
    const { register, watch, setValue, formState } = useForm<BackgroundFormValues>({
        resolver: zodResolver(backgroundSchema),
        defaultValues: {
            singleAllelic: toFormBool(submission.singleAllelic),
            maternalBackground: submission.maternalBackground ?? '',
            paternalBackground: submission.paternalBackground ?? '',
            backgroundChangeable: toFormBool(submission.backgroundChangeable),
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
                },
            }),
    });

    return (
        <section className='section' id='background' aria-labelledby='background-heading'>
            <div className='d-flex justify-content-between align-items-center'>
                <h2 id='background-heading' className='heading'>Background</h2>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <table className='table table-borderless'>
                <tbody>
                    <YesNoRadio
                        name='singleAllelic'
                        label='Single-allelic submission'
                        idPrefix='fr-singleAllelic'
                        register={register}
                    />
                    <BackgroundSelectRow
                        name='maternalBackground'
                        label='Maternal'
                        value={values.maternalBackground}
                        onChange={(v) => setValue('maternalBackground', v, { shouldDirty: true, shouldValidate: true })}
                    />
                    <BackgroundSelectRow
                        name='paternalBackground'
                        label='Paternal'
                        value={values.paternalBackground}
                        onChange={(v) => setValue('paternalBackground', v, { shouldDirty: true, shouldValidate: true })}
                    />
                    <YesNoRadio
                        name='backgroundChangeable'
                        label='Background Changeable'
                        idPrefix='fr-backgroundChangeable'
                        register={register}
                    />
                </tbody>
            </table>
        </section>
    );
}
