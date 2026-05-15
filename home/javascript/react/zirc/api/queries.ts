import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { LineSubmissionResponse } from './types';

export const lineSubmissionKey = (id: string) => ['zirc', 'lineSubmission', id] as const;

export function useLineSubmission(id: string | null) {
    return useQuery({
        queryKey: lineSubmissionKey(id ?? ''),
        queryFn: () => api.get<LineSubmissionResponse>(`/line-submissions/${id}`),
        enabled: !!id,
    });
}

export function useCreateLineSubmission() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: () => api.post<LineSubmissionResponse>('/line-submissions'),
        // Seed the cache so the post-create GET is a hit, not a loading flash.
        onSuccess: (data) => {
            qc.setQueryData(lineSubmissionKey(data.zdbID), data);
        },
    });
}
