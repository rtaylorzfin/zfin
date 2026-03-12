package org.zfin.datatransfer.ncbi;

import org.junit.Test;
import org.zfin.datatransfer.ncbi.NCBIOutputFileToLoad.LoadFileRow;
import org.zfin.datatransfer.ncbi.load.NcbiDiffComputer;
import org.zfin.datatransfer.ncbi.load.NcbiDiffComputer.CurrentDbLink;
import org.zfin.datatransfer.ncbi.load.NcbiDiffComputer.DiffResult;
import org.zfin.datatransfer.ncbi.load.NcbiDiffComputer.DiffUpdate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zfin.datatransfer.ncbi.NCBIDirectPort.*;

public class NcbiDiffComputerTest {

    private final NcbiDiffComputer computer = new NcbiDiffComputer();

    @Test
    public void testEmptyCurrentStateAllAdds() {
        Map<String, CurrentDbLink> current = new HashMap<>();
        NCBIOutputFileToLoad desired = new NCBIOutputFileToLoad();
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "12345", null, FDCONT_NCBI_GENE_ID, PUB_MAPPED_BASED_ON_RNA));
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "NM_001", 500, FDCONT_REFSEQ_RNA, PUB_MAPPED_BASED_ON_RNA));

        DiffResult diff = computer.computeDiff(current, desired);

        assertEquals(2, diff.toAdd().size());
        assertEquals(0, diff.toDeleteZdbIds().size());
        assertEquals(0, diff.toUpdate().size());
        assertEquals(0, diff.kept().size());
    }

    @Test
    public void testEmptyDesiredStateAllDeletes() {
        Map<String, CurrentDbLink> current = new HashMap<>();
        current.put("ZDB-GENE-1|12345|" + FDCONT_NCBI_GENE_ID,
                new CurrentDbLink("ZDB-GENE-1", "12345", FDCONT_NCBI_GENE_ID, "ZDB-DBLINK-100", null, PUB_MAPPED_BASED_ON_RNA));
        current.put("ZDB-GENE-1|NM_001|" + FDCONT_REFSEQ_RNA,
                new CurrentDbLink("ZDB-GENE-1", "NM_001", FDCONT_REFSEQ_RNA, "ZDB-DBLINK-101", 500, PUB_MAPPED_BASED_ON_RNA));

        NCBIOutputFileToLoad desired = new NCBIOutputFileToLoad();

        DiffResult diff = computer.computeDiff(current, desired);

        assertEquals(0, diff.toAdd().size());
        assertEquals(2, diff.toDeleteZdbIds().size());
        assertTrue(diff.toDeleteZdbIds().contains("ZDB-DBLINK-100"));
        assertTrue(diff.toDeleteZdbIds().contains("ZDB-DBLINK-101"));
        assertEquals(0, diff.toUpdate().size());
    }

    @Test
    public void testIdenticalStateAllKept() {
        Map<String, CurrentDbLink> current = new HashMap<>();
        current.put("ZDB-GENE-1|12345|" + FDCONT_NCBI_GENE_ID,
                new CurrentDbLink("ZDB-GENE-1", "12345", FDCONT_NCBI_GENE_ID, "ZDB-DBLINK-100", null, PUB_MAPPED_BASED_ON_RNA));
        current.put("ZDB-GENE-1|NM_001|" + FDCONT_REFSEQ_RNA,
                new CurrentDbLink("ZDB-GENE-1", "NM_001", FDCONT_REFSEQ_RNA, "ZDB-DBLINK-101", 500, PUB_MAPPED_BASED_ON_RNA));

        NCBIOutputFileToLoad desired = new NCBIOutputFileToLoad();
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "12345", null, FDCONT_NCBI_GENE_ID, PUB_MAPPED_BASED_ON_RNA));
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "NM_001", 500, FDCONT_REFSEQ_RNA, PUB_MAPPED_BASED_ON_RNA));

        DiffResult diff = computer.computeDiff(current, desired);

        assertEquals(0, diff.toAdd().size());
        assertEquals(0, diff.toDeleteZdbIds().size());
        assertEquals(0, diff.toUpdate().size());
        assertEquals(2, diff.kept().size());
    }

    @Test
    public void testLengthChangeProducesUpdate() {
        Map<String, CurrentDbLink> current = new HashMap<>();
        current.put("ZDB-GENE-1|NM_001|" + FDCONT_REFSEQ_RNA,
                new CurrentDbLink("ZDB-GENE-1", "NM_001", FDCONT_REFSEQ_RNA, "ZDB-DBLINK-101", 500, PUB_MAPPED_BASED_ON_RNA));

        NCBIOutputFileToLoad desired = new NCBIOutputFileToLoad();
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "NM_001", 600, FDCONT_REFSEQ_RNA, PUB_MAPPED_BASED_ON_RNA));

        DiffResult diff = computer.computeDiff(current, desired);

        assertEquals(0, diff.toAdd().size());
        assertEquals(0, diff.toDeleteZdbIds().size());
        assertEquals(1, diff.toUpdate().size());
        DiffUpdate update = diff.toUpdate().get(0);
        assertEquals("ZDB-DBLINK-101", update.zdbId());
        assertEquals(Integer.valueOf(600), update.newLength());
        assertEquals(Integer.valueOf(500), update.oldLength());
    }

    @Test
    public void testMixedDiff() {
        Map<String, CurrentDbLink> current = new HashMap<>();
        // This record stays (unchanged)
        current.put("ZDB-GENE-1|12345|" + FDCONT_NCBI_GENE_ID,
                new CurrentDbLink("ZDB-GENE-1", "12345", FDCONT_NCBI_GENE_ID, "ZDB-DBLINK-100", null, PUB_MAPPED_BASED_ON_RNA));
        // This record gets deleted (not in desired)
        current.put("ZDB-GENE-2|67890|" + FDCONT_NCBI_GENE_ID,
                new CurrentDbLink("ZDB-GENE-2", "67890", FDCONT_NCBI_GENE_ID, "ZDB-DBLINK-200", null, PUB_MAPPED_BASED_ON_RNA));
        // This record gets updated (length changed)
        current.put("ZDB-GENE-1|NM_001|" + FDCONT_REFSEQ_RNA,
                new CurrentDbLink("ZDB-GENE-1", "NM_001", FDCONT_REFSEQ_RNA, "ZDB-DBLINK-101", 500, PUB_MAPPED_BASED_ON_RNA));

        NCBIOutputFileToLoad desired = new NCBIOutputFileToLoad();
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "12345", null, FDCONT_NCBI_GENE_ID, PUB_MAPPED_BASED_ON_RNA));
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "NM_001", 600, FDCONT_REFSEQ_RNA, PUB_MAPPED_BASED_ON_RNA));
        // This is a new record
        desired.addRow(new LoadFileRow("ZDB-GENE-3", "99999", null, FDCONT_NCBI_GENE_ID, PUB_MAPPED_BASED_ON_RNA));

        DiffResult diff = computer.computeDiff(current, desired);

        assertEquals(1, diff.toAdd().size());
        assertEquals("99999", diff.toAdd().get(0).accession());
        assertEquals(1, diff.toDeleteZdbIds().size());
        assertTrue(diff.toDeleteZdbIds().contains("ZDB-DBLINK-200"));
        assertEquals(1, diff.toUpdate().size());
        assertEquals(1, diff.kept().size());
    }

    @Test
    public void testPubUpgradeProducesUpdate() {
        Map<String, CurrentDbLink> current = new HashMap<>();
        current.put("ZDB-GENE-1|12345|" + FDCONT_NCBI_GENE_ID,
                new CurrentDbLink("ZDB-GENE-1", "12345", FDCONT_NCBI_GENE_ID, "ZDB-DBLINK-100", null, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT));

        NCBIOutputFileToLoad desired = new NCBIOutputFileToLoad();
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "12345", null, FDCONT_NCBI_GENE_ID, PUB_MAPPED_BASED_ON_RNA));

        DiffResult diff = computer.computeDiff(current, desired);

        assertEquals(0, diff.toAdd().size());
        assertEquals(0, diff.toDeleteZdbIds().size());
        assertEquals(1, diff.toUpdate().size());
        DiffUpdate update = diff.toUpdate().get(0);
        assertEquals(PUB_MAPPED_BASED_ON_RNA, update.newPub());
        assertEquals(PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT, update.oldPub());
    }

    @Test
    public void testPubDowngradeKeptUnchanged() {
        // If desired has lower-priority pub than current, don't downgrade
        Map<String, CurrentDbLink> current = new HashMap<>();
        current.put("ZDB-GENE-1|12345|" + FDCONT_NCBI_GENE_ID,
                new CurrentDbLink("ZDB-GENE-1", "12345", FDCONT_NCBI_GENE_ID, "ZDB-DBLINK-100", null, PUB_MAPPED_BASED_ON_RNA));

        NCBIOutputFileToLoad desired = new NCBIOutputFileToLoad();
        desired.addRow(new LoadFileRow("ZDB-GENE-1", "12345", null, FDCONT_NCBI_GENE_ID, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT));

        DiffResult diff = computer.computeDiff(current, desired);

        assertEquals(0, diff.toAdd().size());
        assertEquals(0, diff.toDeleteZdbIds().size());
        assertEquals(0, diff.toUpdate().size());
        assertEquals(1, diff.kept().size());
    }
}
