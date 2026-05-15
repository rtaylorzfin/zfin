import { z } from 'zod';

// Canonical acceptance-reasons list, sourced from the reference form (trunk).
// The SQL schema comment on zirc.line_submission.ls_reasons points to the
// React form as the source of truth — the column is just TEXT[] of snake_case
// values. Renaming a label here doesn't require a DB migration; renaming a
// value does (existing rows store the old value).
export const CANONICAL_REASONS: ReadonlyArray<{ value: string; label: string }> = [
    { value: 'frequently_requested', label: 'Currently frequently requested' },
    { value: 'expect_high_demand', label: 'Expect high demand' },
    { value: 'interesting_gene', label: 'Interesting gene' },
    { value: 'community_resource', label: 'Community resource/tool' },
    { value: 'mutant_gene_cloned', label: 'Mutant gene cloned' },
    { value: 'danger_of_losing', label: 'Danger of losing line' },
    { value: 'lack_of_space_or_funding', label: 'Lack of space or funding to maintain line' },
    { value: 'other', label: 'Other' },
];

export const REASON_OTHER_VALUE = 'other';

export const acceptanceReasonsSchema = z.object({
    reasons: z.array(z.string()),
    reasonsOther: z.string().max(2000, 'Must be 2000 characters or fewer'),
});

export type AcceptanceReasonsFormValues = z.infer<typeof acceptanceReasonsSchema>;
