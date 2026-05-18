import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { AssayDTO, LineSubmissionDTO, MutationDTO } from './types';

export const lineSubmissionKey = (id: string) => ['zirc', 'lineSubmission', id] as const;

export function useLineSubmission(id: string | null) {
    return useQuery({
        queryKey: lineSubmissionKey(id ?? ''),
        queryFn: () => api.get<LineSubmissionDTO>(`/line-submissions/${id}`),
        enabled: !!id,
    });
}

export function useCreateLineSubmission() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: () => api.post<LineSubmissionDTO>('/line-submissions'),
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
            api.post<MutationDTO>(`/line-submissions/${submissionId}/mutations`),
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
        queryFn: () => api.get<MutationDTO>(`/mutations/${id}`),
        enabled: !!id,
    });
}

export function useAddAssay() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (mutationId: number) =>
            api.post<MutationDTO>(`/mutations/${mutationId}/assays`),
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
        queryFn: () => api.get<AssayDTO>(`/assays/${id}`),
        enabled: !!id,
    });
}

export function useUploadAttachment() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ assayId, file }: { assayId: number; file: File }) => {
            const form = new FormData();
            form.append('file', file);
            return api.upload<AssayDTO>(`/assays/${assayId}/attachments`, form);
        },
        onSuccess: (_data, vars) => {
            qc.invalidateQueries({ queryKey: assayKey(vars.assayId) });
        },
    });
}

export function useDeleteAttachment() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: ({ fileId }: { assayId: number; fileId: number }) =>
            api.delete<void>(`/assays/attachments/${fileId}`),
        onSuccess: (_data, vars) => {
            qc.invalidateQueries({ queryKey: assayKey(vars.assayId) });
        },
    });
}
