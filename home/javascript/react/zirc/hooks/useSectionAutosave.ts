import * as React from 'react';

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';

export interface UseSectionAutosaveOptions<TValues> {
    values: TValues;
    isDirty: boolean;
    isValid: boolean;
    save: (values: TValues) => Promise<unknown>;
    debounceMs?: number;
}

export interface UseSectionAutosaveResult {
    status: SaveStatus;
    errorMessage: string | null;
}

/**
 * Section-scoped autosave: debounces by 800ms (override with debounceMs),
 * stays idle until the form goes dirty (so Zod's initial validation pass
 * doesn't trigger a save), and surfaces idle/saving/saved/error status.
 *
 * Values are diffed via JSON.stringify; the section DTOs are small flat
 * objects (a handful of fields), so the cost is negligible and avoids the
 * "useEffect fires every render because watch() returns a new object" trap.
 */
export function useSectionAutosave<TValues>({
    values,
    isDirty,
    isValid,
    save,
    debounceMs = 800,
}: UseSectionAutosaveOptions<TValues>): UseSectionAutosaveResult {
    const [status, setStatus] = React.useState<SaveStatus>('idle');
    const [errorMessage, setErrorMessage] = React.useState<string | null>(null);

    const saveRef = React.useRef(save);
    saveRef.current = save;

    const valuesKey = JSON.stringify(values);

    React.useEffect(() => {
        if (!isDirty) {return;}
        if (!isValid) {
            setStatus('error');
            setErrorMessage('Some fields exceed their length limit.');
            return;
        }

        const handle = window.setTimeout(async () => {
            setStatus('saving');
            setErrorMessage(null);
            try {
                await saveRef.current(values);
                setStatus('saved');
            } catch (e: unknown) {
                setStatus('error');
                setErrorMessage(e instanceof Error ? e.message : 'Save failed');
            }
        }, debounceMs);

        return () => window.clearTimeout(handle);
    }, [valuesKey, isDirty, isValid, debounceMs]);

    return { status, errorMessage };
}
