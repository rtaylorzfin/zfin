package org.zfin.zirc.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zfin.zirc.api.uischema.Control;
import org.zfin.zirc.api.uischema.Group;
import org.zfin.zirc.api.uischema.Options;
import org.zfin.zirc.api.uischema.Rule;
import org.zfin.zirc.api.uischema.UiSchemaElement;
import org.zfin.zirc.api.uischema.VerticalLayout;
import org.zfin.zirc.entity.GenotypingAssay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Single source of truth for the per-assay edit form (M4.2). Parallels
 * {@link ZircFormSchema} / {@link ZircMutationFormSchema} but for the
 * {@link GenotypingAssay} aggregate.
 *
 * <p>The {@code assayType} dropdown at the top of the form drives conditional
 * reveal of field clusters via JSON Forms {@code rule} blocks with
 * {@code schema.enum} matchers. Each cluster lives in its own Group so the
 * rule applies to the whole block, not per-field.
 *
 * <p>Field/cluster matrix:
 * <ul>
 *   <li><b>General</b> — assayType, additionalInfo (always visible)</li>
 *   <li><b>PCR primers</b> — fwd/rev primers + expected PCR sizes; PCR, RFLP, dCAPS, sequencing, KASP</li>
 *   <li><b>Restriction digest</b> — RFLP, dCAPS</li>
 *   <li><b>Sequencing primer</b> — sequencing</li>
 *   <li><b>dCAPS mismatch primer</b> — dCAPS</li>
 *   <li><b>Allele-specific PCR</b> — AS-PCR</li>
 *   <li><b>KASP genomic sequence</b> — KASP</li>
 *   <li><b>SSLP</b> — SSLP</li>
 * </ul>
 *
 * <p>Canonical assay-type list is a starter — curators should review.
 */
public final class ZircAssayFormSchema {

    private ZircAssayFormSchema() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Read + write for one form-schema path on a GenotypingAssay. */
    public record FieldDescriptor(
            Function<GenotypingAssay, JsonNode> read,
            BiConsumer<GenotypingAssay, JsonNode> write) {
    }

    private static final List<String> ASSAY_TYPES = List.of(
            "PCR", "RFLP", "dCAPS", "sequencing", "AS-PCR", "KASP", "SSLP");

    private static final List<String> PCR_PRIMER_TYPES =
            List.of("PCR", "RFLP", "dCAPS", "sequencing", "KASP");
    private static final List<String> DIGEST_TYPES =
            List.of("RFLP", "dCAPS");
    private static final List<String> SEQUENCING_TYPES =
            List.of("sequencing");
    private static final List<String> DCAPS_TYPES =
            List.of("dCAPS");
    private static final List<String> ASPCR_TYPES =
            List.of("AS-PCR");
    private static final List<String> KASP_TYPES =
            List.of("KASP");
    private static final List<String> SSLP_TYPES =
            List.of("SSLP");

    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        // General
        properties.put("assayType",                stringProp(255, "Assay Type"));
        properties.put("additionalInfo",           stringProp(5000, "Additional Info"));
        // PCR primers
        properties.put("forwardPrimer",            stringProp(2000, "Forward Primer"));
        properties.put("reversePrimer",            stringProp(2000, "Reverse Primer"));
        properties.put("expectedWtPcr",            stringProp(2000, "Expected WT PCR"));
        properties.put("expectedMutPcr",           stringProp(2000, "Expected Mutant PCR"));
        // Sequencing
        properties.put("sequencingPrimer",         stringProp(2000, "Sequencing Primer"));
        // dCAPS
        properties.put("dcapsMismatchPrimer",      stringProp(2000, "dCAPS Mismatch Primer"));
        // Allele-specific PCR
        properties.put("wtSpecificPrimer",         stringProp(2000, "WT-Specific Primer"));
        properties.put("mutSpecificPrimer",        stringProp(2000, "Mutant-Specific Primer"));
        properties.put("commonPrimer",             stringProp(2000, "Common Primer"));
        // KASP
        properties.put("kaspGenomicSequence",      stringProp(5000, "KASP Genomic Sequence"));
        // RFLP
        properties.put("restrictionEnzymeName",    stringProp(255, "Restriction Enzyme Name"));
        properties.put("restrictionEnzymeCatalog", stringProp(255, "Restriction Enzyme Catalog #"));
        properties.put("enzymeCleaves",            stringListProp("Enzyme Cleaves At"));
        properties.put("expectedWtDigest",         stringProp(2000, "Expected WT Digest"));
        properties.put("expectedMutDigest",        stringProp(2000, "Expected Mutant Digest"));
        // SSLP
        properties.put("sslpMarkerName",           stringProp(255, "SSLP Marker Name"));
        properties.put("sslpDistance",             stringProp(255, "SSLP Distance"));
        properties.put("sslpGenomicLocation",      stringProp(255, "SSLP Genomic Location"));
        properties.put("sslpInducedBackground",    stringProp(255, "SSLP Induced Background"));
        properties.put("sslpOutcrossedBackground", stringProp(255, "SSLP Outcrossed Background"));
        properties.put("sslpInducedPcr",           stringProp(2000, "SSLP Induced PCR"));
        properties.put("sslpOutcrossedPcr",        stringProp(2000, "SSLP Outcrossed PCR"));
        // Attachments — summary rows; uploads happen through a dedicated
        // multipart endpoint, not the field-path PATCH (AssayEdit's diff
        // filter must skip /attachments).
        properties.put("attachments",              attachmentsArrayProp());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        return root;
    }

    public static UiSchemaElement uiSchema() {
        return new VerticalLayout(List.of(
                Group.of("General", List.of(
                        new Control("#/properties/assayType",
                                Options.of().widget("selectWithOther").standardValues(ASSAY_TYPES),
                                null),
                        new Control("#/properties/additionalInfo",
                                Options.of().multi(true), null)
                )),
                groupRevealedFor("PCR Primers", PCR_PRIMER_TYPES, List.of(
                        new Control("#/properties/forwardPrimer",
                                Options.of().placeholder("5′ → 3′ sequence"), null),
                        new Control("#/properties/reversePrimer",
                                Options.of().placeholder("5′ → 3′ sequence"), null),
                        new Control("#/properties/expectedWtPcr",
                                Options.of()
                                        .suffix("bp")
                                        .helpText("Expected amplicon size on a wild-type template."),
                                null),
                        new Control("#/properties/expectedMutPcr",
                                Options.of()
                                        .suffix("bp")
                                        .helpText("Expected amplicon size on a mutant template."),
                                null)
                )),
                groupRevealedFor("Restriction Digest", DIGEST_TYPES, List.of(
                        new Control("#/properties/restrictionEnzymeName",
                                Options.of().placeholder("e.g. BsmBI"), null),
                        new Control("#/properties/restrictionEnzymeCatalog",
                                Options.of()
                                        .placeholder("vendor + cat #")
                                        .infoHref("https://international.neb.com/"),
                                null),
                        new Control("#/properties/enzymeCleaves",
                                Options.of()
                                        .widget("stringList")
                                        .helpText("One sequence per row; positions where the enzyme cuts."),
                                null),
                        new Control("#/properties/expectedWtDigest",
                                Options.of().suffix("bp"), null),
                        new Control("#/properties/expectedMutDigest",
                                Options.of().suffix("bp"), null)
                )),
                groupRevealedFor("Sequencing", SEQUENCING_TYPES, List.of(
                        Control.of("#/properties/sequencingPrimer")
                )),
                groupRevealedFor("dCAPS Mismatch", DCAPS_TYPES, List.of(
                        Control.of("#/properties/dcapsMismatchPrimer")
                )),
                groupRevealedFor("Allele-Specific PCR", ASPCR_TYPES, List.of(
                        Control.of("#/properties/wtSpecificPrimer"),
                        Control.of("#/properties/mutSpecificPrimer"),
                        Control.of("#/properties/commonPrimer")
                )),
                groupRevealedFor("KASP", KASP_TYPES, List.of(
                        new Control("#/properties/kaspGenomicSequence",
                                Options.of().multi(true), null)
                )),
                groupRevealedFor("SSLP", SSLP_TYPES, List.of(
                        Control.of("#/properties/sslpMarkerName"),
                        Control.of("#/properties/sslpDistance"),
                        Control.of("#/properties/sslpGenomicLocation"),
                        Control.of("#/properties/sslpInducedBackground"),
                        Control.of("#/properties/sslpOutcrossedBackground"),
                        Control.of("#/properties/sslpInducedPcr"),
                        Control.of("#/properties/sslpOutcrossedPcr")
                )),
                // Attachments is always shown — kind matrix is intentionally
                // collapsed to a single "Files" affordance for now.
                new Group("Attachments",
                        List.of(new Control("#/properties/attachments",
                                Options.of().widget("attachmentsList"), null)),
                        Options.of().layout("plain"),
                        null)
        ));
    }

    /**
     * Tiny helper specific to the assay-type matrix: a Group revealed
     * when {@code assayType} is one of the listed values.
     */
    private static Group groupRevealedFor(
            String label, List<String> assayTypes, List<UiSchemaElement> elements) {
        return new Group(label, elements, null,
                Rule.showWhenIn("#/properties/assayType", assayTypes));
    }

    /**
     * Path → read+write dispatch for assay fields. Same gatekeeper behavior
     * as the other two FIELDS maps: unknown paths are rejected at the
     * controller. enzymeCleaves uses a list-rewrite write so Hibernate sees
     * a new array instance per save.
     */
    public static final Map<String, FieldDescriptor> FIELDS = Map.ofEntries(
            field("/assayType",                GenotypingAssay::getAssayType,                (a, v) -> a.setAssayType(text(v))),
            field("/additionalInfo",           GenotypingAssay::getAdditionalInfo,           (a, v) -> a.setAdditionalInfo(text(v))),
            field("/forwardPrimer",            GenotypingAssay::getForwardPrimer,            (a, v) -> a.setForwardPrimer(text(v))),
            field("/reversePrimer",            GenotypingAssay::getReversePrimer,            (a, v) -> a.setReversePrimer(text(v))),
            field("/expectedWtPcr",            GenotypingAssay::getExpectedWtPcr,            (a, v) -> a.setExpectedWtPcr(text(v))),
            field("/expectedMutPcr",           GenotypingAssay::getExpectedMutPcr,           (a, v) -> a.setExpectedMutPcr(text(v))),
            field("/sequencingPrimer",         GenotypingAssay::getSequencingPrimer,         (a, v) -> a.setSequencingPrimer(text(v))),
            field("/dcapsMismatchPrimer",      GenotypingAssay::getDcapsMismatchPrimer,      (a, v) -> a.setDcapsMismatchPrimer(text(v))),
            field("/wtSpecificPrimer",         GenotypingAssay::getWtSpecificPrimer,         (a, v) -> a.setWtSpecificPrimer(text(v))),
            field("/mutSpecificPrimer",        GenotypingAssay::getMutSpecificPrimer,        (a, v) -> a.setMutSpecificPrimer(text(v))),
            field("/commonPrimer",             GenotypingAssay::getCommonPrimer,             (a, v) -> a.setCommonPrimer(text(v))),
            field("/kaspGenomicSequence",      GenotypingAssay::getKaspGenomicSequence,      (a, v) -> a.setKaspGenomicSequence(text(v))),
            field("/restrictionEnzymeName",    GenotypingAssay::getRestrictionEnzymeName,    (a, v) -> a.setRestrictionEnzymeName(text(v))),
            field("/restrictionEnzymeCatalog", GenotypingAssay::getRestrictionEnzymeCatalog, (a, v) -> a.setRestrictionEnzymeCatalog(text(v))),
            field("/enzymeCleaves",            GenotypingAssay::getEnzymeCleaves,            (a, v) -> a.setEnzymeCleaves(stringArray(v))),
            field("/expectedWtDigest",         GenotypingAssay::getExpectedWtDigest,         (a, v) -> a.setExpectedWtDigest(text(v))),
            field("/expectedMutDigest",        GenotypingAssay::getExpectedMutDigest,        (a, v) -> a.setExpectedMutDigest(text(v))),
            field("/sslpMarkerName",           GenotypingAssay::getSslpMarkerName,           (a, v) -> a.setSslpMarkerName(text(v))),
            field("/sslpDistance",             GenotypingAssay::getSslpDistance,             (a, v) -> a.setSslpDistance(text(v))),
            field("/sslpGenomicLocation",      GenotypingAssay::getSslpGenomicLocation,      (a, v) -> a.setSslpGenomicLocation(text(v))),
            field("/sslpInducedBackground",    GenotypingAssay::getSslpInducedBackground,    (a, v) -> a.setSslpInducedBackground(text(v))),
            field("/sslpOutcrossedBackground", GenotypingAssay::getSslpOutcrossedBackground, (a, v) -> a.setSslpOutcrossedBackground(text(v))),
            field("/sslpInducedPcr",           GenotypingAssay::getSslpInducedPcr,           (a, v) -> a.setSslpInducedPcr(text(v))),
            field("/sslpOutcrossedPcr",        GenotypingAssay::getSslpOutcrossedPcr,        (a, v) -> a.setSslpOutcrossedPcr(text(v)))
    );

    // ─── schema builders ────────────────────────────────────────────────────

    private static Map<String, Object> stringProp(int maxLength, String title) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("title", title);
        p.put("maxLength", maxLength);
        return p;
    }

    private static Map<String, Object> stringListProp(String title) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("title", title);
        p.put("items", items);
        return p;
    }

    /**
     * Mirror of {@link org.zfin.zirc.dto.AssayFileDTO}; the renderer
     * reads the summary fields. File content is fetched via the streaming
     * endpoint, not as part of the form data.
     */
    /** Hard cap on attachments per assay; same MAX_CHILD_ROWS_PER_MUTATION shape from the alt branch. */
    public static final int MAX_ATTACHMENTS_PER_ASSAY = 10;

    private static Map<String, Object> attachmentsArrayProp() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("id",               Map.of("type", "number"));
        itemProps.put("originalFilename", Map.of("type", "string"));
        itemProps.put("contentType",      Map.of("type", List.of("string", "null")));
        itemProps.put("fileSize",         Map.of("type", List.of("number", "null")));
        itemProps.put("uploadedAt",       Map.of("type", List.of("string", "null")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);

        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("title", "Attachments");
        arr.put("items", item);
        arr.put("maxItems", MAX_ATTACHMENTS_PER_ASSAY);
        return arr;
    }

    // ─── uiSchema builders ──────────────────────────────────────────────────
    // (now in org.zfin.zirc.api.uischema; construct VerticalLayout/Group/Control
    //  records directly above. groupRevealedFor is the local helper for the
    //  assay-type matrix's repeated "SHOW when assayType in […]" pattern.)

    // ─── descriptor builders ────────────────────────────────────────────────

    private static Map.Entry<String, FieldDescriptor> field(
            String path,
            Function<GenotypingAssay, ?> getter,
            BiConsumer<GenotypingAssay, JsonNode> setter) {
        return Map.entry(path, new FieldDescriptor(
                a -> MAPPER.valueToTree(getter.apply(a)),
                setter));
    }

    // ─── value coercers ────────────────────────────────────────────────────

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) {return null;}
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }

    private static String[] stringArray(JsonNode v) {
        if (v == null || v.isNull() || !v.isArray()) {return new String[0];}
        List<String> kept = new java.util.ArrayList<>(v.size());
        for (int i = 0; i < v.size(); i++) {
            String s = v.get(i).asText();
            if (s != null && !s.isBlank()) {
                kept.add(s.trim());
            }
        }
        return kept.toArray(new String[0]);
    }
}
