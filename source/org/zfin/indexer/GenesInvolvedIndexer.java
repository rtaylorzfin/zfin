package org.zfin.indexer;

import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import lombok.extern.log4j.Log4j2;
import org.zfin.framework.HibernateUtil;
import org.zfin.framework.api.Pagination;
import org.zfin.marker.Marker;
import org.zfin.ontology.GenericTerm;
import org.zfin.ontology.OmimPhenotypeDisplay;
import org.zfin.ontology.service.OntologyService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;

import static org.zfin.repository.RepositoryFactory.getOntologyRepository;

@Log4j2
public class GenesInvolvedIndexer extends UiIndexer<OmimPhenotypeDisplay> {

    public GenesInvolvedIndexer(UiIndexerConfig config) {
        super(config);
    }

    @Override
    protected List<OmimPhenotypeDisplay> inputOutput() {
        Set<GenericTerm> diseaseList = getOntologyRepository().getDiseaseTermsOmimPhenotype();
        List<OmimPhenotypeDisplay> resultList = new ArrayList<>(1000);
        for (GenericTerm term : diseaseList) {
            List<OmimPhenotypeDisplay> displayListSingle = OntologyService.getOmimPhenotype(term, new Pagination(), false);
            OntologyService.fixupSearchColumns(displayListSingle);
            resultList.addAll(displayListSingle);
        }
        Set<String> termIds = resultList.stream()
            .filter(d -> d.getDisease() != null)
            .map(d -> d.getDisease().getZdbID())
            .collect(Collectors.toSet());
        Map<String, String[]> ancestorMap = getOntologyRepository().getAncestorTermIdsBulk(termIds);
        resultList.forEach(d -> {
            if (d.getDisease() != null) {
                d.setAncestorTermIds(ancestorMap.getOrDefault(d.getDisease().getZdbID(), new String[0]));
            }
        });
        return resultList;
    }

    /**
     * Merge on the natural key (disease term, human gene, OMIM accession, OMIM
     * phenotype name), matched NULL-safely since term and accession can be NULL,
     * instead of delete-all + insert-all, so a row whose key survives keeps its
     * opd_id (which is in API JSON) and the ZFIN gene association only changes where
     * its content does. New rows take their id from omim_display_display_seq, the
     * sequence the entity's generator uses (the column default's lags far behind).
     */
    @Override
    protected boolean mergesInPlace() {
        return true;
    }

    @Override
    protected void mergeOutput(List<OmimPhenotypeDisplay> output) {
        HibernateUtil.createTransaction();
        HibernateUtil.currentSession().doWork(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute(CREATE_STAGE);
            }
            try (PreparedStatement rows = connection.prepareStatement("insert into opd_stage values (?,?,?,?,?,?)");
                 PreparedStatement genes = connection.prepareStatement("insert into oza_stage values (?,?,?,?,?)")) {
                int n = 0;
                for (OmimPhenotypeDisplay display : output) {
                    String term = display.getDisease() == null ? null : display.getDisease().getZdbID();
                    String humanGene = display.getHomoSapiensGene() == null ? null : display.getHomoSapiensGene().getId();
                    rows.setString(1, term);
                    rows.setString(2, humanGene);
                    rows.setString(3, display.getOmimAccession());
                    rows.setString(4, display.getName());
                    rows.setString(5, display.getZfinGeneSymbolSearch());
                    if (display.getAncestorTermIds() == null) {
                        rows.setNull(6, Types.ARRAY);
                    } else {
                        rows.setArray(6, connection.createArrayOf("text", display.getAncestorTermIds()));
                    }
                    rows.addBatch();
                    if (display.getZfinGene() != null) {
                        for (Marker gene : display.getZfinGene()) {
                            genes.setString(1, term);
                            genes.setString(2, humanGene);
                            genes.setString(3, display.getOmimAccession());
                            genes.setString(4, display.getName());
                            genes.setString(5, gene.getZdbID());
                            genes.addBatch();
                        }
                    }
                    if (++n % 5000 == 0) {
                        rows.executeBatch();
                        genes.executeBatch();
                    }
                }
                rows.executeBatch();
                genes.executeBatch();
            }
            try (Statement st = connection.createStatement()) {
                for (String sql : MERGE.split(";\\s*\n")) {
                    if (!sql.isBlank()) {
                        st.execute(sql);
                    }
                }
            }
        });
        HibernateUtil.flushAndCommitCurrentSession();
    }

    private static final String CREATE_STAGE = """
        create temp table opd_stage (
            term text, human_gene text, omim_accession text, omim_name text,
            gene_symbols_search text, ancestor_term_ids text[]
        ) on commit drop;
        create temp table oza_stage (term text, human_gene text, omim_accession text, omim_name text, gene text) on commit drop
        """;

    // Statements are separated by ";" + newline (see mergeOutput). Runs in the same
    // transaction as the staging, so readers never see a half-applied table.
    static final String MERGE = """
        analyze opd_stage;
        analyze oza_stage;

        -- rows whose key is gone, and their association rows (no FK cascade)
        create temp table opd_gone on commit drop as
          select d.opd_id from ui.omim_phenotype_display d
           where not exists (select 1 from opd_stage s where coalesce(s.term, '') = coalesce(d.opd_term_zdb_id, '') and coalesce(s.human_gene, '') = coalesce(d.opd_human_gene_id, '') and coalesce(s.omim_accession, '') = coalesce(d.opd_omim_accession, '') and coalesce(s.omim_name, '') = coalesce(d.opd_omim_term_name, ''));
        delete from ui.omim_zfin_association where oza_human_phenotype_id in (select opd_id from opd_gone);
        delete from ui.omim_phenotype_display where opd_id in (select opd_id from opd_gone);

        -- surviving keys: update only rows whose content changed (opd_id and created_at stay)
        update ui.omim_phenotype_display d
           set opd_zfin_gene_symbols_search = s.gene_symbols_search, opd_ancestor_term_ids = s.ancestor_term_ids
          from opd_stage s
         where coalesce(s.term, '') = coalesce(d.opd_term_zdb_id, '') and coalesce(s.human_gene, '') = coalesce(d.opd_human_gene_id, '') and coalesce(s.omim_accession, '') = coalesce(d.opd_omim_accession, '') and coalesce(s.omim_name, '') = coalesce(d.opd_omim_term_name, '')
           and (d.opd_zfin_gene_symbols_search, d.opd_ancestor_term_ids)
               is distinct from (s.gene_symbols_search, s.ancestor_term_ids);

        -- new keys get a fresh opd_id from the entity's sequence
        insert into ui.omim_phenotype_display
               (opd_id, opd_term_zdb_id, opd_human_gene_id, opd_omim_accession, opd_omim_term_name,
                opd_zfin_gene_symbols_search, opd_ancestor_term_ids)
        select nextval('public.omim_display_display_seq'), s.term, s.human_gene, s.omim_accession, s.omim_name,
               s.gene_symbols_search, s.ancestor_term_ids
          from opd_stage s
         where not exists (select 1 from ui.omim_phenotype_display d where coalesce(s.term, '') = coalesce(d.opd_term_zdb_id, '') and coalesce(s.human_gene, '') = coalesce(d.opd_human_gene_id, '') and coalesce(s.omim_accession, '') = coalesce(d.opd_omim_accession, '') and coalesce(s.omim_name, '') = coalesce(d.opd_omim_term_name, ''));

        -- ZFIN gene association: wanted (opd_id, gene) pairs; drop pairs that are gone, add new ones
        create temp table oza_want on commit drop as
          select distinct d.opd_id, g.gene from oza_stage g
            join ui.omim_phenotype_display d on coalesce(g.term, '') = coalesce(d.opd_term_zdb_id, '') and coalesce(g.human_gene, '') = coalesce(d.opd_human_gene_id, '') and coalesce(g.omim_accession, '') = coalesce(d.opd_omim_accession, '') and coalesce(g.omim_name, '') = coalesce(d.opd_omim_term_name, '');
        delete from ui.omim_zfin_association a
         where not exists (select 1 from oza_want w
                            where w.opd_id = a.oza_human_phenotype_id and w.gene = a.oza_zfin_gene_zdb_id);
        insert into ui.omim_zfin_association (oza_human_phenotype_id, oza_zfin_gene_zdb_id)
        select w.opd_id, w.gene from oza_want w
         where not exists (select 1 from ui.omim_zfin_association a
                            where a.oza_human_phenotype_id = w.opd_id and a.oza_zfin_gene_zdb_id = w.gene)
        """;

    /** Not used while mergesInPlace() is true; kept for a full rebuild. */
    @Override
    protected void cleanUiTables() {
        try {
            String schema = OmimPhenotypeDisplay.class.getDeclaredField("zfinGene").getAnnotation(JoinTable.class).schema();
            String associationTable = OmimPhenotypeDisplay.class.getDeclaredField("zfinGene").getAnnotation(JoinTable.class).name();
            String omimTable = OmimPhenotypeDisplay.class.getAnnotation(Table.class).name();
            cleanoutTable(schema, associationTable, omimTable);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
}
