import * as React from 'react';
import { SaveStatus } from '../hooks/useSectionAutosave';

export function SaveStatusBadge({
    status,
    message,
}: {
    status: SaveStatus;
    message: string | null;
}) {
    if (status === 'idle') {return null;}
    if (status === 'saving') {return <span className='text-muted small'>Saving…</span>;}
    if (status === 'saved') {return <span className='text-success small'>Saved</span>;}
    return (
        <span className='text-danger small' title={message ?? ''}>
            Error{message ? `: ${message}` : ''}
        </span>
    );
}
