import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { JsonForms } from '@jsonforms/react';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { api } from '../api/client';
import { LineSubmissionResponse } from '../api/types';
import { useCreateLineSubmission } from '../api/queries';
import { SaveStatusBadge } from '../components/SaveStatusBadge';
import { SaveStatus } from '../hooks/useSectionAutosave';
import { sectionRendererEntry } from './renderers/SectionRenderer';
import { rowControlRendererEntry } from './renderers/RowControlRenderer';

type FormDataShape = {
    name?: string;
    previousNames?: string;
};

type FormSchemaResponse = { schema: JsonSchema; uiSchema: UISchemaElement };

type Props = {
    submission: LineSubmissionResponse | null;
    onCreated: (s: LineSubmissionResponse) => void;
};

const AUTOSAVE_DEBOUNCE_MS = 800;

// JSON Forms requires an explicit renderer registry; only the reference-styled
// ones are registered, so any unknown widget kind (number, array of objects,
// etc.) surfaces as a "no matching renderer" — by design, the schema can only
// describe what we know how to render.
const renderers = [sectionRendererEntry, rowControlRendererEntry];

/**
 * Schema-driven Overview form. The Java side at GET /api/zirc/form-schema is
 * the single source of truth for both the JSON Schema and the JSON Forms
 * uiSchema; the client just dispatches them to JsonForms with our reference-
 * styled renderers and emits one field-path PATCH per changed leaf on save.
 */
export function SchemaForm({ submission, onCreated }: Props) {
    const { data: schemaResponse, isLoading: schemaLoading, isError: schemaError } =
        useQuery<FormSchemaResponse>({
            queryKey: ['zirc', 'form-schema'],
            queryFn: () => api.get<FormSchemaResponse>('/form-schema'),
            staleTime: Infinity,
        });

    const initialData: FormDataShape = {
        name: submission?.name ?? '',
        previousNames: submission?.previousNames ?? '',
    };

    const [formData, setFormData] = React.useState<FormDataShape>(initialData);
    const submissionIdRef = React.useRef<string | null>(submission?.zdbID ?? null);
    const lastSavedRef = React.useRef<FormDataShape>(initialData);
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
            // Diff against last persisted state — one PATCH per changed leaf
            // so the server-side audit log captures each field change.
            const changes: Array<[string, unknown]> = [];
            (Object.keys(formData) as Array<keyof FormDataShape>).forEach((key) => {
                if (!Object.is(formData[key], lastSavedRef.current[key])) {
                    changes.push([`/${key}`, formData[key] ?? null]);
                }
            });
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
                    const fieldName = path.slice(1) as keyof FormDataShape;
                    lastSavedRef.current = { ...lastSavedRef.current, [fieldName]: value as string };
                }
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
                onChange={({ data }) => setFormData(data as FormDataShape)}
            />
        </div>
    );
}
