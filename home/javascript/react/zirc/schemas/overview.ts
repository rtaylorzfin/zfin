import { z } from 'zod';

// Mirrors @Size constraints on org.zfin.zirc.dto.LineSubmissionOverviewUpdate.
// Server validation is authoritative; this catches obvious problems before a save round-trip.
export const overviewSchema = z.object({
    name: z.string().max(255, 'Must be 255 characters or fewer'),
    abbreviation: z.string().max(255, 'Must be 255 characters or fewer'),
    previousNames: z.string().max(2000, 'Must be 2000 characters or fewer'),
});

export type OverviewFormValues = z.infer<typeof overviewSchema>;
