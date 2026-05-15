import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { JsonForms } from '@jsonforms/react';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { api } from '../api/client';
import { LineSubmissionResponse } from '../api/types';
import { useCreateLineSubmission } from '../api/queries';
import { SaveStatusBadge, SaveStatus } from '../components/SaveStatusBadge';
import { sectionRendererEntry } from './renderers/SectionRenderer';
import { rowControlRendererEntry } from './renderers/RowControlRenderer';
import { verticalLayoutRendererEntry } from './renderers/VerticalLayoutRenderer';
import { textareaRowRendererEntry } from './renderers/TextareaRowRenderer';
import { yesNoRadioRendererEntry } from './renderers/YesNoRadioRenderer';
import { multipleChoiceWithOtherRendererEntry } from './renderers/MultipleChoiceWithOtherRenderer';
import { backgroundSelectWithOtherRendererEntry } from './renderers/BackgroundSelectWithOtherRenderer';

type FormData = Record<string, unknown>;

type FormSchemaResponse = { schema: JsonSchema; uiSchema: UISchemaElement };

type Props = {
    submission: LineSubmissionResponse | null;
    onCreated: (s: LineSubmissionResponse) => void;
};

const AUTOSAVE_DEBOUNCE_MS = 800;

const renderers = [
    verticalLayoutRendererEntry,
    sectionRendererEntry,
    rowControlRendererEntry,
    textareaRowRendererEntry,
    yesNoRadioRendererEntry,
    multipleChoiceWithOtherRendererEntry,
    backgroundSelectWithOtherRendererEntry,
];

function initialDataFromSubmission(submission: LineSubmissionResponse | null): FormData {
    if (!submission) {
        return {
            name: '',
            previousNames: '',
            acceptance: { reasons: [], reasonsOther: '' },
            background: {
                singleAllelic: null,
                maternalBackground: '',
                paternalBackground: '',
                backgroundChangeable: null,
            },
            additionalInfo: {
                unreportedFeaturesDetails: '',
                husbandryInfo: '',
                additionalInfo: '',
            },
        };
    }
    return {
        name: submission.name ?? '',
        previousNames: submission.previousNames ?? '',
        acceptance: {
            reasons: submission.reasons ?? [],
            reasonsOther: submission.reasonsOther ?? '',
        },
        background: {
            singleAllelic: submission.singleAllelic,
            maternalBackground: submission.maternalBackground ?? '',
            paternalBackground: submission.paternalBackground ?? '',
            backgroundChangeable: submission.backgroundChangeable,
        },
        additionalInfo: {
            unreportedFeaturesDetails: submission.unreportedFeaturesDetails ?? '',
            husbandryInfo: submission.husbandryInfo ?? '',
            additionalInfo: submission.additionalInfo ?? '',
        },
    };
}

/**
 * Walks two form-data trees and emits one [path, value] entry per leaf that
 * changed. Arrays are treated as leaves (the whole list is sent as one
 * value, since the schema models reasons[] as an atomic chip-list). Path is
 * JSON Pointer (`/acceptance/reasons`).
 */
function diffLeaves(
    prev: unknown,
    curr: unknown,
    basePath = '',
): Array<[string, unknown]> {
    const isPlainObject = (v: unknown): v is Record<string, unknown> =>
        typeof v === 'object' && v !== null && !Array.isArray(v);

    if (Array.isArray(prev) || Array.isArray(curr)) {
        if (JSON.stringify(prev ?? null) !== JSON.stringify(curr ?? null)) {
            return [[basePath || '/', curr ?? null]];
        }
        return [];
    }
    if (isPlainObject(prev) && isPlainObject(curr)) {
        const keys = new Set([...Object.keys(prev), ...Object.keys(curr)]);
        const changes: Array<[string, unknown]> = [];
        for (const key of keys) {
            changes.push(...diffLeaves(prev[key], curr[key], `${basePath}/${key}`));
        }
        return changes;
    }
    if (!Object.is(prev, curr)) {
        return [[basePath || '/', curr ?? null]];
    }
    return [];
}

/**
 * Schema-driven submission form. Server's GET /api/zirc/form-schema returns
 * both the JSON Schema and the JSON Forms uiSchema; this component just
 * dispatches them to JsonForms with our reference-styled renderers, then
 * emits one field-path PATCH per changed leaf when the user pauses typing.
 */
export function SchemaForm({ submission, onCreated }: Props) {
    const { data: schemaResponse, isLoading: schemaLoading, isError: schemaError } =
        useQuery<FormSchemaResponse>({
            queryKey: ['zirc', 'form-schema'],
            queryFn: () => api.get<FormSchemaResponse>('/form-schema'),
            staleTime: Infinity,
        });

    const initialData = React.useMemo(() => initialDataFromSubmission(submission), [submission?.zdbID]);
    const [formData, setFormData] = React.useState<FormData>(initialData);
    const submissionIdRef = React.useRef<string | null>(submission?.zdbID ?? null);
    const lastSavedRef = React.useRef<FormData>(initialData);
    const create = useCreateLineSubmission();

    const [status, setStatus] = React.useState<SaveStatus>('idle');
    const [errorMessage, setErrorMessage] = React.useState<string | null>(null);
    const firstRender = React.useRef(true);

    const formDataKey = JSON.stringify(formData);

    React.useEffect(() => {
        if (firstRender.current) {
            firstRender.current = false;
            return;
        }

        const handle = window.setTimeout(async () => {
            const changes = diffLeaves(lastSavedRef.current, formData);
            if (changes.length === 0) {return;}

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
                        `/action/zirc/line-submission/${id}/edit`,
                    );
                }
                for (const [path, value] of changes) {
                    await api.patch<LineSubmissionResponse>(
                        `/line-submissions/${id}`,
                        { path, value },
                    );
                }
                lastSavedRef.current = formData;
                setStatus('saved');
            } catch (e: unknown) {
                setStatus('error');
                setErrorMessage(e instanceof Error ? e.message : 'Save failed');
            }
        }, AUTOSAVE_DEBOUNCE_MS);

        return () => window.clearTimeout(handle);
    }, [formDataKey]);

    if (schemaLoading) {return <p className='text-muted'>Loading form schema…</p>;}
    if (schemaError || !schemaResponse) {
        return <div className='alert alert-danger'>Failed to load form schema.</div>;
    }

    return (
        <div>
            <div className='d-flex justify-content-end mb-1'>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <JsonForms
                schema={schemaResponse.schema}
                uischema={schemaResponse.uiSchema}
                data={formData}
                renderers={renderers}
                cells={[]}
                onChange={({ data }) => setFormData(data as FormData)}
            />
        </div>
    );
}
