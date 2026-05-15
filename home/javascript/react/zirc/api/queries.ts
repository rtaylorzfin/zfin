import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { AssayResponse, LineSubmissionResponse, MutationResponse } from './types';

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

export function useAddMutation() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (submissionId: string) =>
            api.post<MutationResponse>(`/line-submissions/${submissionId}/mutations`),
        onSuccess: (_data, submissionId) => {
            qc.invalidateQueries({ queryKey: lineSubmissionKey(submissionId) });
        },
    });
}

export function useDeleteMutation() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ submissionId, mutationId }: { submissionId: string; mutationId: number }) =>
            api.delete<void>(`/line-submissions/${submissionId}/mutations/${mutationId}`),
        onSuccess: (_data, vars) => {
            qc.invalidateQueries({ queryKey: lineSubmissionKey(vars.submissionId) });
        },
    });
}

export const mutationKey = (id: number) => ['zirc', 'mutation', id] as const;

export function useMutationById(id: number | null) {
    return useQuery({
        queryKey: mutationKey(id ?? 0),
        queryFn: () => api.get<MutationResponse>(`/mutations/${id}`),
        enabled: !!id,
    });
}

export function useAddAssay() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (mutationId: number) =>
            api.post<MutationResponse>(`/mutations/${mutationId}/assays`),
        onSuccess: (_data, mutationId) => {
            qc.invalidateQueries({ queryKey: mutationKey(mutationId) });
        },
    });
}

export function useDeleteAssay() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ assayId }: { mutationId: number; assayId: number }) =>
            api.delete<void>(`/assays/${assayId}`),
        onSuccess: (_data, vars) => {
            qc.invalidateQueries({ queryKey: mutationKey(vars.mutationId) });
        },
    });
}

export const assayKey = (id: number) => ['zirc', 'assay', id] as const;

export function useAssayById(id: number | null) {
    return useQuery({
        queryKey: assayKey(id ?? 0),
        queryFn: () => api.get<AssayResponse>(`/assays/${id}`),
        enabled: !!id,
    });
}
