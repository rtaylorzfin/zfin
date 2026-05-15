import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { JsonForms } from '@jsonforms/react';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { api } from '../../api/client';
import { LineSubmissionResponse } from '../../api/types';
import { useCreateLineSubmission } from '../../api/queries';
import { SaveStatusBadge } from '../../components/SaveStatusBadge';
import { SaveStatus } from '../../hooks/useSectionAutosave';
import { sectionRendererEntry } from './renderers/SectionRenderer';
import { rowControlRendererEntry } from './renderers/RowControlRenderer';

type FormDataShape = {
    name?: string;
    previousNames?: string;
};

type FormSchemaResponse = { schema: JsonSchema; uiSchema: object };

type Props = {
    submission: LineSubmissionResponse | null;
    onCreated: (s: LineSubmissionResponse) => void;
};

const AUTOSAVE_DEBOUNCE_MS = 800;

// JSON Forms requires an explicit renderer registry; we mount only the
// reference-styled ones for the spike. Anything outside this set (numbers,
// arrays, etc.) would fall through to "no matching renderer" — fine since
// Overview is all strings.
const renderers = [sectionRendererEntry, rowControlRendererEntry];

// Hand-rolled uiSchema for the spike. For multi-section work the server
// would emit this alongside the schema; here we build it client-side from
// the schema's top-level title and property names.
function buildUiSchema(schema: JsonSchema): UISchemaElement {
    const title = (schema as { title?: string }).title ?? 'Overview';
    const props = (schema as { properties?: Record<string, unknown> }).properties ?? {};
    return {
        type: 'Group',
        label: title,
        elements: Object.keys(props).map((name) => ({
            type: 'Control',
            scope: `#/properties/${name}`,
        })),
    } as UISchemaElement;
}

/**
 * JSON Forms parallel of SchemaForm — same backend, same audit log, same
 * field-path PATCH on the wire. Only the rendering library differs.
 */
export function JsonFormsSchemaForm({ submission, onCreated }: Props) {
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

    const uiSchema = React.useMemo(
        () => schemaResponse && buildUiSchema(schemaResponse.schema),
        [schemaResponse],
    );

    const formDataKey = JSON.stringify(formData);

    React.useEffect(() => {
        if (firstRender.current) {
            firstRender.current = false;
            return;
        }

        const handle = window.setTimeout(async () => {
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
    if (schemaError || !schemaResponse || !uiSchema) {
        return <div className='alert alert-danger'>Failed to load form schema.</div>;
    }

    return (
        <div>
            <div className='d-flex justify-content-end mb-1'>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <JsonForms
                schema={schemaResponse.schema}
                uischema={uiSchema}
                data={formData}
                renderers={renderers}
                cells={[]}
                onChange={({ data }) => setFormData(data as FormDataShape)}
            />
        </div>
    );
}
