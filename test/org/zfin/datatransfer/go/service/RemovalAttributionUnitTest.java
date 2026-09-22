package org.zfin.datatransfer.go.service;

import org.junit.Test;
import org.zfin.datatransfer.go.GafEntry;
import org.zfin.datatransfer.go.GafJobEntry;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The removal-safety guard's core, without a database.
 *
 * <p>These exist because the previous guard was wrong in a way that looked right at runtime: it
 * logged "REMOVAL BLOCKED", set the exit code, and withheld nothing, because it identified an
 * entry's owning organization by comparing against {@code organizationCreatedBy} -- the GPAD
 * {@code assigned_by} column, a different namespace entirely. Observed in production: it announced
 * blocking 47,138 deletions and then reported removing 47,138. A green build proved nothing, so
 * the fix is only credible with tests that fail when the matching stops matching.
 */
public class RemovalAttributionUnitTest {

    private static GafEntry rejected(String entityId, String goTermId) {
        GafEntry entry = new GafEntry();
        entry.setEntryId(entityId);
        entry.setGoTermId(goTermId);
        return entry;
    }

    private static GafJobEntry removal(String markerZdbID, String goTermID, String owningOrg) {
        GafJobEntry entry = new GafJobEntry("ZDB-MRKRGOEV-TEST-1");
        entry.setOwningOrganization(owningOrg);
        entry.setMarkerZdbID(markerZdbID);
        entry.setGoTermID(goTermID);
        return entry;
    }

    @Test
    public void gpadEntityIdPrefixIsStripped() {
        Set<String> keys = GafService.attributionKeys(List.of(
            rejected("ZFIN:ZDB-GENE-000112-47", "GO:0090575")));
        assertEquals(Set.of(GafService.attributionKey("ZDB-GENE-000112-47", "GO:0090575")), keys);
    }

    @Test
    public void unresolvableSubjectsAreNotAttributed() {
        // A UniProtKB accession (the GAF path) or a rejection that never resolved a gene cannot be
        // tied to any particular removal. Guessing here would withhold arbitrary rows.
        Set<String> keys = GafService.attributionKeys(List.of(
            rejected("UniProtKB:A0A0G2KC95", "GO:0006357"),
            rejected(null, "GO:0006357"),
            rejected("ZFIN:ZDB-GENE-1", null)));
        assertTrue("nothing should be attributable from these", keys.isEmpty());
    }

    @Test
    public void aRemovalMatchingARejectedRowIsWithheld() {
        Set<String> keys = GafService.attributionKeys(List.of(
            rejected("ZFIN:ZDB-GENE-1", "GO:0000001")));
        List<GafJobEntry> hit = GafService.removalsAttributableTo(
            List.of(removal("ZDB-GENE-1", "GO:0000001", "GOA")), "GOA", keys);
        assertEquals(1, hit.size());
    }

    @Test
    public void aRemovalWithNoMatchingRejectionIsApplied() {
        // The file genuinely dropped this row. Withholding it would be wrong, and a guard that
        // withheld it would block every legitimate first cutover.
        Set<String> keys = GafService.attributionKeys(List.of(
            rejected("ZFIN:ZDB-GENE-1", "GO:0000001")));
        List<GafJobEntry> hit = GafService.removalsAttributableTo(
            List.of(removal("ZDB-GENE-2", "GO:0000002", "GOA")), "GOA", keys);
        assertTrue(hit.isEmpty());
    }

    @Test
    public void removalsOfAnotherOrganizationAreNotTouched() {
        Set<String> keys = GafService.attributionKeys(List.of(
            rejected("ZFIN:ZDB-GENE-1", "GO:0000001")));
        List<GafJobEntry> hit = GafService.removalsAttributableTo(
            List.of(removal("ZDB-GENE-1", "GO:0000001", "Noctua")), "GOA", keys);
        assertTrue("a GOA pass must not withhold Noctua's removals", hit.isEmpty());
    }

    /**
     * The regression test for the original defect. organizationCreatedBy carries assigned_by
     * values -- UniProt, InterPro, ZFIN, GO_Central -- which never equal the owning organization
     * names the load prunes. Matching must not depend on that field.
     */
    @Test
    public void matchingDoesNotDependOnOrganizationCreatedBy() {
        Set<String> keys = GafService.attributionKeys(List.of(
            rejected("ZFIN:ZDB-GENE-1", "GO:0000001")));
        GafJobEntry entry = removal("ZDB-GENE-1", "GO:0000001", "GOA");
        // assigned_by is "InterPro" and shares no namespace with "GOA"; the old guard compared
        // exactly these two strings and therefore matched nothing.
        entry.setOrganizationCreatedBy("InterPro");
        assertEquals(1, GafService.removalsAttributableTo(List.of(entry), "GOA", keys).size());
    }

    @Test
    public void untaggedRemovalsAreNeverWithheld() {
        // The ND-replacement path records removals with no owning organization, and those rows
        // have already been deleted by the time they are recorded.
        Set<String> keys = GafService.attributionKeys(List.of(
            rejected("ZFIN:ZDB-GENE-1", "GO:0000001")));
        assertTrue(GafService.removalsAttributableTo(
            List.of(removal("ZDB-GENE-1", "GO:0000001", null)), "GOA", keys).isEmpty());
    }

    @Test
    public void noRejectionsMeansNothingIsEverWithheld() {
        assertTrue(GafService.removalsAttributableTo(
            List.of(removal("ZDB-GENE-1", "GO:0000001", "GOA")), "GOA", Set.of()).isEmpty());
    }
}
