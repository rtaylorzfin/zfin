import { z } from 'zod';

// Tri-state boolean encoded as a string for form binding. Empty string means
// "not yet picked" (initial state for a fresh draft). Once the user picks
// Yes or No the value stays one of 'true'/'false'; the reference form has no
// "Unspecified" option, so we don't render one. Conversion to boolean|null
// happens in the form's save callback.
export const triBool = z.enum(['true', 'false', '']);
export type TriBool = z.infer<typeof triBool>;

// Standard background options shown in the select. Anything else is shown
// via the "Other" sentinel + revealed text input. The value the server
// receives is the actual string (one of these, or the free-text override).
export const STANDARD_BACKGROUNDS: ReadonlyArray<string> = [
    'AB',
    'TU',
    'WIK',
    'AB/TU',
    'unknown',
];

export const backgroundSchema = z.object({
    singleAllelic: triBool,
    maternalBackground: z.string().max(255, 'Must be 255 characters or fewer'),
    paternalBackground: z.string().max(255, 'Must be 255 characters or fewer'),
    backgroundChangeable: triBool,
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
