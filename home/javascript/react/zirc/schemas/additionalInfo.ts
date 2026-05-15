import { z } from 'zod';

export const additionalInfoSchema = z.object({
    unreportedFeaturesDetails: z.string().max(5000, 'Must be 5000 characters or fewer'),
    husbandryInfo: z.string().max(5000, 'Must be 5000 characters or fewer'),
    additionalInfo: z.string().max(5000, 'Must be 5000 characters or fewer'),
});

export type AdditionalInfoFormValues = z.infer<typeof additionalInfoSchema>;
