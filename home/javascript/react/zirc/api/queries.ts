import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { LineSubmissionResponse, OverviewUpdate } from './types';

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

export function useUpdateOverview() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ id, update }: { id: string; update: OverviewUpdate }) =>
            api.patch<LineSubmissionResponse>(`/line-submissions/${id}/overview`, update),
        onSuccess: (data) => {
            qc.setQueryData(lineSubmissionKey(data.zdbID), data);
        },
    });
}
