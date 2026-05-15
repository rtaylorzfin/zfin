import { z } from 'zod';

// Canonical acceptance-reasons list lives here (the SQL schema comment on
// zirc.line_submission.ls_reasons points to the React form as the source of
// truth — the column is just TEXT[] of snake_case values). Curators should
// review this list; renaming a label here doesn't require a DB migration,
// renaming a value does (existing rows store the old value).
export const CANONICAL_REASONS: ReadonlyArray<{ value: string; label: string }> = [
    { value: 'frequently_requested', label: 'Frequently requested by other labs' },
    { value: 'expect_high_demand', label: 'Expected to be in high demand' },
    { value: 'unique_genetic_background', label: 'Unique genetic background' },
    { value: 'supports_active_research', label: 'Supports active research programs' },
    { value: 'disease_model', label: 'Useful zebrafish model of human disease' },
    { value: 'preserves_loss_of_function', label: 'Preserves an important loss-of-function allele' },
    { value: 'supports_collaboration', label: 'Supports ongoing collaborations' },
    { value: 'other', label: 'Other (please describe)' },
];

export const REASON_OTHER_VALUE = 'other';

export const acceptanceReasonsSchema = z.object({
    reasons: z.array(z.string()),
    reasonsOther: z.string().max(2000, 'Must be 2000 characters or fewer'),
});

export type AcceptanceReasonsFormValues = z.infer<typeof acceptanceReasonsSchema>;
