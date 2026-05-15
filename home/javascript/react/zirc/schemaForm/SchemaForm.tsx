import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import Form from '@rjsf/bootstrap-4';
import validator from '@rjsf/validator-ajv8';
import type { RJSFSchema, UiSchema } from '@rjsf/utils';
import { api } from '../api/client';
import { LineSubmissionResponse } from '../api/types';
import { useCreateLineSubmission } from '../api/queries';
import { SaveStatusBadge } from '../components/SaveStatusBadge';
import { SaveStatus } from '../hooks/useSectionAutosave';
import { SectionObjectFieldTemplate } from './templates/SectionObjectFieldTemplate';
import { RowFieldTemplate } from './templates/RowFieldTemplate';

type FormDataShape = {
    name?: string;
    previousNames?: string;
};

type FormSchemaResponse = { schema: RJSFSchema; uiSchema: UiSchema };

type Props = {
    submission: LineSubmissionResponse | null;
    onCreated: (s: LineSubmissionResponse) => void;
};

const AUTOSAVE_DEBOUNCE_MS = 800;

// Custom templates produce the reference markup (<section class="section">,
// <h2 class="heading">, <table class="table table-borderless">, fr-* ids).
// Once a uiSchema arrives that opts into our custom widgets (autocomplete,
// MultipleChoiceWithOther, etc.) the same template set still applies.
const TEMPLATES = {
    ObjectFieldTemplate: SectionObjectFieldTemplate,
    FieldTemplate: RowFieldTemplate,
};

/**
 * Spike renderer for the schema-driven Overview section. Subscribes to the
 * /form-schema endpoint, lets rjsf draw the inputs through our reference-
 * styled templates, and fires one field-path PATCH per changed leaf when
 * the user pauses typing.
 *
 * Limited to Overview for the spike; conditional reveals, custom widgets,
 * and nested sections come if we adopt the pattern.
 */
export function SchemaForm({ submission, onCreated }: Props) {
    const { data: schemaResponse, isLoading: schemaLoading, isError: schemaError } =
        useQuery<FormSchemaResponse>({
            queryKey: ['zirc', 'form-schema'],
            queryFn: () => api.get<FormSchemaResponse>('/form-schema'),
            staleTime: Infinity, // schema is static for a session; refetch on hard reload
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
            // Diff current form data against last persisted state — emit one
            // PATCH per changed leaf so the server-side audit log captures
            // each field change discretely.
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
            {/* Save status sits above the form; the ObjectFieldTemplate
                produces the section/h2/table inside the Form, so we don't
                wrap a section here. */}
            <div className='d-flex justify-content-end mb-1'>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <Form
                schema={schemaResponse.schema}
                uiSchema={schemaResponse.uiSchema}
                formData={formData}
                onChange={(e) => setFormData(e.formData as FormDataShape)}
                validator={validator}
                templates={TEMPLATES}
                idPrefix='fr'
                idSeparator='-'
                liveValidate
            >
                {/* hide the default Submit button — we autosave */}
                <></>
            </Form>
        </div>
    );
}
