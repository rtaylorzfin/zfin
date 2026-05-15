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
    // General
    alleleDesignation: string | null;
    alleleInZfin: boolean | null;
    mutationType: string | null;
    mutationDiscoverer: string | null;
    mutationInstitution: string | null;
    // Mutagenesis
    mutagenesisStage: string | null;
    mutagenesisProtocol: string | null;
    molecularlyCharacterized: boolean | null;
    // Lethality
    homozygousLethal: boolean | null;
    lethalityStageTypical: string | null;
    lethalitySpecificTimepoint: string | null;
    lethalityWindowStart: string | null;
    lethalityWindowEnd: string | null;
    lethalityAdditionalInfo: string | null;
    // Publications
    publications: string[];
    // Genotyping assays — summary rows only, surfaced as collapsed cards.
    assays: AssaySummary[];
}

export interface AssaySummary {
    id: number;
    sortOrder: number;
    assayType: string | null;
}

// Full per-assay payload — used by the inline assay editor (M4.2). Field
// visibility is decided by the uiSchema's conditional rules, not by which
// fields are populated, so every column shows up here regardless of type.
export interface AssayResponse {
    id: number;
    mutationId: number | null;
    sortOrder: number;
    assayType: string | null;
    // PCR core
    forwardPrimer: string | null;
    reversePrimer: string | null;
    expectedWtPcr: string | null;
    expectedMutPcr: string | null;
    // Sequencing
    sequencingPrimer: string | null;
    // dCAPS
    dcapsMismatchPrimer: string | null;
    // Allele-specific PCR
    wtSpecificPrimer: string | null;
    mutSpecificPrimer: string | null;
    commonPrimer: string | null;
    // KASP
    kaspGenomicSequence: string | null;
    // RFLP
    restrictionEnzymeName: string | null;
    restrictionEnzymeCatalog: string | null;
    enzymeCleaves: string[];
    expectedWtDigest: string | null;
    expectedMutDigest: string | null;
    // SSLP
    sslpMarkerName: string | null;
    sslpDistance: string | null;
    sslpGenomicLocation: string | null;
    sslpInducedBackground: string | null;
    sslpOutcrossedBackground: string | null;
    sslpInducedPcr: string | null;
    sslpOutcrossedPcr: string | null;
    // Catch-all
    additionalInfo: string | null;
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
