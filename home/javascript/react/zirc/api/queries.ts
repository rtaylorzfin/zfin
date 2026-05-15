import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import {
    AcceptanceReasonsUpdate,
    AdditionalInfoUpdate,
    BackgroundUpdate,
    LineSubmissionResponse,
    OverviewUpdate,
} from './types';

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

// One factory for each section PATCH: same shape, just different path + DTO.
function sectionMutation<TUpdate>(path: (id: string) => string) {
    return () => {
        const qc = useQueryClient();
        return useMutation({
            mutationFn: ({ id, update }: { id: string; update: TUpdate }) =>
                api.patch<LineSubmissionResponse>(path(id), update),
            onSuccess: (data) => {
                qc.setQueryData(lineSubmissionKey(data.zdbID), data);
            },
        });
    };
}

export const useUpdateOverview = sectionMutation<OverviewUpdate>(
    (id) => `/line-submissions/${id}/overview`,
);
export const useUpdateAcceptanceReasons = sectionMutation<AcceptanceReasonsUpdate>(
    (id) => `/line-submissions/${id}/acceptance-reasons`,
);
export const useUpdateBackground = sectionMutation<BackgroundUpdate>(
    (id) => `/line-submissions/${id}/background`,
);
export const useUpdateAdditionalInfo = sectionMutation<AdditionalInfoUpdate>(
    (id) => `/line-submissions/${id}/additional-info`,
);
