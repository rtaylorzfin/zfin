import { z } from 'zod';

// Tri-state boolean encoded as a string so it round-trips through form inputs
// (radio buttons can only hold strings). Conversion to boolean|null happens in
// the form's save callback. Empty string means "Unspecified".
export const triBool = z.enum(['true', 'false', '']);
export type TriBool = z.infer<typeof triBool>;

export const backgroundSchema = z.object({
    singleAllelic: triBool,
    maternalBackground: z.string().max(255, 'Must be 255 characters or fewer'),
    paternalBackground: z.string().max(255, 'Must be 255 characters or fewer'),
    backgroundChangeable: triBool,
    backgroundChangeConcerns: z.string().max(2000, 'Must be 2000 characters or fewer'),
});

export type BackgroundFormValues = z.infer<typeof backgroundSchema>;

export function toFormBool(b: boolean | null | undefined): TriBool {
    if (b === true) {return 'true';}
    if (b === false) {return 'false';}
    return '';
}

export function fromFormBool(s: TriBool): boolean | null {
    if (s === 'true') {return true;}
    if (s === 'false') {return false;}
    return null;
}
