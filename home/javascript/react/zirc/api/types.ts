// Hand-typed mirrors of the org.zfin.zirc.dto.* Java records.
// Replace with OpenAPI-generated types once springdoc is wired (see milestone 2 backlog).

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

export interface OverviewUpdate {
    name?: string | null;
    abbreviation?: string | null;
    previousNames?: string | null;
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
