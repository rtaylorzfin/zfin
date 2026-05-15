import { ProblemDetail } from './types';

const API_BASE = '/action/api/zirc';

export class ApiError extends Error {
    constructor(public status: number, public problem: ProblemDetail) {
        super(problem.title || problem.detail || `HTTP ${status}`);
        this.name = 'ApiError';
    }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const hasBody = init.body !== undefined && init.body !== null;
    const response = await fetch(API_BASE + path, {
        ...init,
        headers: {
            Accept: 'application/json, application/problem+json',
            ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
            ...init.headers,
        },
    });

    if (!response.ok) {
        let problem: ProblemDetail;
        try {
            problem = await response.json();
        } catch {
            problem = { title: response.statusText, status: response.status };
        }
        throw new ApiError(response.status, problem);
    }

    if (response.status === 204) {return undefined as unknown as T;}
    return response.json() as Promise<T>;
}

export const api = {
    get: <T>(path: string) => request<T>(path),
    post: <T>(path: string, body?: unknown) =>
        request<T>(path, {
            method: 'POST',
            body: body === undefined ? undefined : JSON.stringify(body),
        }),
    patch: <T>(path: string, body: unknown) =>
        request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
    delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};
