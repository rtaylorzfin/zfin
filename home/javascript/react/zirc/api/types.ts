// Hand-typed mirror of org.zfin.zirc.dto.LineSubmissionResponse, used by the
// React Query cache and as the seed for the schema-driven form's initial data.

export interface LineSubmissionResponse {
    zdbID: string;
    name: string | null;
    abbreviation: string | null;
    previousNames: string | null;
    singleAllelic: boolean | null;
    maternalBackground: string | null;
    paternalBackground: string | null;
    backgroundChangeable: boolean | null;
    backgroundChangeConcerns: string | null;
    unreportedFeaturesDetails: string | null;
    husbandryInfo: string | null;
    additionalInfo: string | null;
    reasons: string[];
    reasonsOther: string | null;
    mutations: MutationResponse[];
    draft: boolean;
}

export interface MutationResponse {
    id: number;
    lineSubmissionId: string;
    sortOrder: number;
    alleleDesignation: string | null;
    alleleInZfin: boolean | null;
    mutationType: string | null;
}

// RFC 7807 problem detail returned by ZircApiExceptionHandler.
export interface ProblemDetail {
    type?: string;
    title?: string;
    status?: number;
    detail?: string;
    instance?: string;
    errors?: Record<string, string>;
}
