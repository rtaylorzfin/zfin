import * as React from 'react';
import { QueryClientProvider, useQuery } from '@tanstack/react-query';
import { JsonForms } from '@jsonforms/react';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { queryClient } from '../queryClient';
import { api } from '../api/client';
import { MutationResponse } from '../api/types';
import { useMutationById } from '../api/queries';
import { SaveStatusBadge, SaveStatus } from '../components/SaveStatusBadge';
import { sectionRendererEntry } from '../schemaForm/renderers/SectionRenderer';
import { rowControlRendererEntry } from '../schemaForm/renderers/RowControlRenderer';
import { verticalLayoutRendererEntry } from '../schemaForm/renderers/VerticalLayoutRenderer';
import { textareaRowRendererEntry } from '../schemaForm/renderers/TextareaRowRenderer';
import { yesNoRadioRendererEntry } from '../schemaForm/renderers/YesNoRadioRenderer';
import { selectWithOtherRendererEntry } from '../schemaForm/renderers/SelectWithOtherRenderer';
import { publicationsListRendererEntry } from '../schemaForm/renderers/PublicationsListRenderer';

export type MutationEditProps = {
    // From data-mutation-id on the JSP mount.
    mutationId?: string;
    submissionId?: string;
};

export default function MutationEdit(props: MutationEditProps) {
    return (
        <QueryClientProvider client={queryClient}>
            <MutationEditInner {...props} />
        </QueryClientProvider>
    );
}

type FormDataShape = {
    // General
    alleleDesignation?: string | null;
    alleleInZfin?: boolean | null;
    mutationType?: string | null;
    mutationDiscoverer?: string | null;
    mutationInstitution?: string | null;
    // Mutagenesis
    mutagenesisStage?: string | null;
    mutagenesisProtocol?: string | null;
    molecularlyCharacterized?: boolean | null;
    // Lethality
    homozygousLethal?: boolean | null;
    lethalityStageTypical?: string | null;
    lethalitySpecificTimepoint?: string | null;
    lethalityWindowStart?: string | null;
    lethalityWindowEnd?: string | null;
    lethalityAdditionalInfo?: string | null;
    // Publications
    publications?: string[];
};

type FormSchemaResponse = { schema: JsonSchema; uiSchema: UISchemaElement };

const AUTOSAVE_DEBOUNCE_MS = 800;

const renderers = [
    verticalLayoutRendererEntry,
    sectionRendererEntry,
    rowControlRendererEntry,
    textareaRowRendererEntry,
    yesNoRadioRendererEntry,
    selectWithOtherRendererEntry,
    publicationsListRendererEntry,
];

function initialDataFromMutation(m: MutationResponse | undefined): FormDataShape {
    if (!m) {
        return {
            alleleDesignation: '',
            alleleInZfin: null,
            mutationType: '',
            mutationDiscoverer: '',
            mutationInstitution: '',
            mutagenesisStage: '',
            mutagenesisProtocol: '',
            molecularlyCharacterized: null,
            homozygousLethal: null,
            lethalityStageTypical: '',
            lethalitySpecificTimepoint: '',
            lethalityWindowStart: '',
            lethalityWindowEnd: '',
            lethalityAdditionalInfo: '',
            publications: [],
        };
    }
    return {
        alleleDesignation: m.alleleDesignation ?? '',
        alleleInZfin: m.alleleInZfin,
        mutationType: m.mutationType ?? '',
        mutationDiscoverer: m.mutationDiscoverer ?? '',
        mutationInstitution: m.mutationInstitution ?? '',
        mutagenesisStage: m.mutagenesisStage ?? '',
        mutagenesisProtocol: m.mutagenesisProtocol ?? '',
        molecularlyCharacterized: m.molecularlyCharacterized,
        homozygousLethal: m.homozygousLethal,
        lethalityStageTypical: m.lethalityStageTypical ?? '',
        lethalitySpecificTimepoint: m.lethalitySpecificTimepoint ?? '',
        lethalityWindowStart: m.lethalityWindowStart ?? '',
        lethalityWindowEnd: m.lethalityWindowEnd ?? '',
        lethalityAdditionalInfo: m.lethalityAdditionalInfo ?? '',
        publications: m.publications ?? [],
    };
}

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

function MutationEditInner({ mutationId, submissionId }: MutationEditProps) {
    const idNum = mutationId ? Number(mutationId) : null;

    const schemaQuery = useQuery<FormSchemaResponse>({
        queryKey: ['zirc', 'mutation-form-schema'],
        queryFn: () => api.get<FormSchemaResponse>('/mutations/form-schema'),
        staleTime: Infinity,
    });
    const mutationQuery = useMutationById(idNum);

    const mutation = mutationQuery.data;
    const initialData = React.useMemo(() => initialDataFromMutation(mutation), [mutation?.id]);
    const [formData, setFormData] = React.useState<FormDataShape>(initialData);
    const lastSavedRef = React.useRef<FormDataShape>(initialData);
    const initialized = React.useRef(false);

    // Once the server data arrives, seed the form state. Subsequent re-fetches
    // (e.g. after editing in another tab) are ignored — the local state is the
    // source of truth during the edit session.
    React.useEffect(() => {
        if (!mutation || initialized.current) {return;}
        const seed = initialDataFromMutation(mutation);
        setFormData(seed);
        lastSavedRef.current = seed;
        initialized.current = true;
    }, [mutation?.id]);

    const [status, setStatus] = React.useState<SaveStatus>('idle');
    const [errorMessage, setErrorMessage] = React.useState<string | null>(null);
    const firstChange = React.useRef(true);

    const formDataKey = JSON.stringify(formData);

    React.useEffect(() => {
        if (firstChange.current) {
            firstChange.current = false;
            return;
        }
        if (!idNum) {return;}

        const handle = window.setTimeout(async () => {
            const changes = diffLeaves(lastSavedRef.current, formData);
            if (changes.length === 0) {return;}

            setStatus('saving');
            setErrorMessage(null);
            try {
                for (const [path, value] of changes) {
                    await api.patch<MutationResponse>(
                        `/mutations/${idNum}`,
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

    if (!idNum) {
        return <div className='alert alert-danger'>Missing mutation id.</div>;
    }
    if (schemaQuery.isLoading || mutationQuery.isLoading) {
        return <p className='text-muted'>Loading…</p>;
    }
    if (schemaQuery.isError || mutationQuery.isError || !schemaQuery.data || !mutation) {
        return <div className='alert alert-danger'>Failed to load mutation form.</div>;
    }

    return (
        <div>
            <div className='d-flex justify-content-end mb-1'>
                <SaveStatusBadge status={status} message={errorMessage} />
            </div>
            <JsonForms
                schema={schemaQuery.data.schema}
                uischema={schemaQuery.data.uiSchema}
                data={formData}
                renderers={renderers}
                cells={[]}
                config={{ mutationId: idNum, submissionId }}
                onChange={({ data }) => setFormData(data as FormDataShape)}
            />
        </div>
    );
}
