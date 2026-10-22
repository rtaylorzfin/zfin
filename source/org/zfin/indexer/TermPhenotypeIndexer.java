package org.zfin.indexer;

import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import lombok.extern.log4j.Log4j2;
import org.zfin.expression.Figure;
import org.zfin.marker.Marker;
import org.zfin.mutant.Fish;
import org.zfin.mutant.PhenotypeStatementWarehouse;
import org.zfin.mutant.presentation.FishStatistics;
import org.zfin.ontology.GenericTerm;
import org.zfin.publication.Publication;
import org.zfin.repository.RepositoryFactory;

import org.zfin.framework.HibernateUtil;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;

import static org.zfin.repository.RepositoryFactory.getOntologyRepository;
import static org.zfin.util.ZfinCollectionUtils.firstInEachGrouping;

@Log4j2
public class TermPhenotypeIndexer extends UiIndexer<FishStatistics> {

    public TermPhenotypeIndexer(UiIndexerConfig config) {
        super(config);
    }

    @Override
    protected List<FishStatistics> inputOutput() {
        Map<Fish, Map<GenericTerm, List<PhenotypeStatementWarehouse>>> figureMap = RepositoryFactory.getPublicationRepository().getAllFiguresForPhenotype();
        List<FishStatistics> resultList = new ArrayList<>();
        figureMap.forEach((fish, termMap) -> termMap.forEach((term, phenotypeStatementWarehouses) -> {
            FishStatistics stat = new FishStatistics(fish, term);
            stat.setAffectedGenes(new HashSet<>(fish.getAffectedGenes()));
            // remove phenotype statements with the same display name
            SortedSet<PhenotypeStatementWarehouse> set1 = new TreeSet<>(firstInEachGrouping(phenotypeStatementWarehouses, PhenotypeStatementWarehouse::getShortName));
            stat.setPhenotypeStatements(set1);
            Set<Figure> figs = phenotypeStatementWarehouses.stream().map(warehouse -> warehouse.getPhenotypeWarehouse().getFigure()).collect(Collectors.toSet());
            stat.setNumberOfFigs(figs.size());
            if (figs.size() == 1) {
                stat.setFigure(figs.iterator().next());
            }
            Set<Publication> pubs = phenotypeStatementWarehouses.stream().map(warehouse -> warehouse.getPhenotypeWarehouse().getFigure().getPublication()).collect(Collectors.toSet());
            stat.setNumberOfPubs(pubs.size());
            if (pubs.size() == 1) {
                stat.setPublication(pubs.iterator().next());
            }
            stat.setFishSearch(fish.getName().replaceAll("<[^>]*>", ""));
            stat.setPhenotypeStatementSearch(phenotypeStatementWarehouses.stream().map(PhenotypeStatementWarehouse::getShortName).collect(Collectors.joining("|")));
            stat.setGeneSymbolSearch(fish.getAffectedGenes().stream().map(Marker::getAbbreviation).sorted().collect(Collectors.joining("|")));
            resultList.add(stat);
        }));
        Set<String> termIds = resultList.stream()
            .filter(s -> s.getTerm() != null)
            .map(s -> s.getTerm().getZdbID())
            .collect(Collectors.toSet());
        Map<String, String[]> ancestorMap = getOntologyRepository().getAncestorTermIdsBulk(termIds);
        resultList.forEach(s -> {
            if (s.getTerm() != null) {
                s.setAncestorTermIds(ancestorMap.getOrDefault(s.getTerm().getZdbID(), new String[0]));
            }
        });
        return resultList;
    }

    /**
     * Merge on the natural key (term, fish) instead of delete-all + insert-all, so a
     * row whose key survives keeps its tpd_id (which is in client URLs) and both
     * association tables only change where their content does.
     */
    @Override
    protected boolean mergesInPlace() {
        return true;
    }

    @Override
    protected void mergeOutput(List<FishStatistics> output) {
        HibernateUtil.createTransaction();
        HibernateUtil.currentSession().doWork(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute(CREATE_STAGE);
            }
            try (PreparedStatement rows = connection.prepareStatement(
                     "insert into tpd_stage values (?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement genes = connection.prepareStatement("insert into pza_stage values (?,?,?)");
                 PreparedStatement statements = connection.prepareStatement("insert into pwa_stage values (?,?,?)")) {
                int n = 0;
                for (FishStatistics stat : output) {
                    String term = stat.getTerm() == null ? null : stat.getTerm().getZdbID();
                    String fish = stat.getFish().getZdbID();
                    rows.setString(1, term);
                    rows.setString(2, fish);
                    rows.setString(3, stat.getFigure() == null ? null : stat.getFigure().getZdbID());
                    rows.setString(4, stat.getPublication() == null ? null : stat.getPublication().getZdbID());
                    rows.setInt(5, stat.getNumberOfPubs());
                    rows.setInt(6, stat.getNumberOfFigs());
                    rows.setString(7, stat.getFishSearch());
                    rows.setString(8, stat.getGeneSymbolSearch());
                    rows.setString(9, stat.getPhenotypeStatementSearch());
                    if (stat.getAncestorTermIds() == null) {
                        rows.setNull(10, Types.ARRAY);
                    } else {
                        Array ancestors = connection.createArrayOf("text", stat.getAncestorTermIds());
                        rows.setArray(10, ancestors);
                    }
                    rows.addBatch();
                    for (Marker gene : stat.getAffectedGenes()) {
                        genes.setString(1, term);
                        genes.setString(2, fish);
                        genes.setString(3, gene.getZdbID());
                        genes.addBatch();
                    }
                    for (PhenotypeStatementWarehouse warehouse : stat.getPhenotypeStatements()) {
                        statements.setString(1, term);
                        statements.setString(2, fish);
                        statements.setLong(3, warehouse.getId());
                        statements.addBatch();
                    }
                    if (++n % 5000 == 0) {
                        rows.executeBatch();
                        genes.executeBatch();
                        statements.executeBatch();
                    }
                }
                rows.executeBatch();
                genes.executeBatch();
                statements.executeBatch();
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
        create temp table tpd_stage (
            term text, fish text, fig text, pub text, pub_count int, fig_count int,
            fish_search text, gene_search text, phenotype_search text, ancestor_term_ids text[]
        ) on commit drop;
        create temp table pza_stage (term text, fish text, gene text) on commit drop;
        create temp table pwa_stage (term text, fish text, warehouse_id bigint) on commit drop
        """;

    // Statements are separated by ";" + newline (see mergeOutput). Runs in the same
    // transaction as the staging, so readers never see a half-applied table.
    static final String MERGE = """
        create index on tpd_stage (term, fish);
        analyze tpd_stage;
        analyze pza_stage;
        analyze pwa_stage;

        -- rows whose (term, fish) is gone, and their association rows (no FK cascade)
        create temp table tpd_gone on commit drop as
          select d.tpd_id from ui.term_phenotype_display d
           where not exists (select 1 from tpd_stage s
                              where s.term = d.tpd_term_zdb_id and s.fish = d.tpd_fish_zdb_id);
        delete from ui.phenotype_warehouse_association where pwa_phenotype_id in (select tpd_id from tpd_gone);
        delete from ui.phenotype_zfin_association where pza_phenotype_id in (select tpd_id from tpd_gone);
        delete from ui.term_phenotype_display where tpd_id in (select tpd_id from tpd_gone);

        -- surviving keys: update only rows whose content changed (tpd_id and created_at stay)
        update ui.term_phenotype_display d
           set tpd_fig_zdb_id = s.fig, tpd_pub_zdb_id = s.pub,
               tpd_pub_count = s.pub_count, tpd_fig_count = s.fig_count,
               tpd_fish_search = s.fish_search, tpd_gene_search = s.gene_search,
               tpd_phenotype_search = s.phenotype_search, tpd_ancestor_term_ids = s.ancestor_term_ids
          from tpd_stage s
         where s.term = d.tpd_term_zdb_id and s.fish = d.tpd_fish_zdb_id
           and (d.tpd_fig_zdb_id, d.tpd_pub_zdb_id, d.tpd_pub_count, d.tpd_fig_count, d.tpd_fish_search,
                d.tpd_gene_search, d.tpd_phenotype_search, d.tpd_ancestor_term_ids)
               is distinct from
               (s.fig, s.pub, s.pub_count, s.fig_count, s.fish_search,
                s.gene_search, s.phenotype_search, s.ancestor_term_ids);

        -- new keys get a fresh tpd_id
        insert into ui.term_phenotype_display
               (tpd_term_zdb_id, tpd_fish_zdb_id, tpd_fig_zdb_id, tpd_pub_zdb_id, tpd_pub_count, tpd_fig_count,
                tpd_fish_search, tpd_gene_search, tpd_phenotype_search, tpd_ancestor_term_ids)
        select s.term, s.fish, s.fig, s.pub, s.pub_count, s.fig_count,
               s.fish_search, s.gene_search, s.phenotype_search, s.ancestor_term_ids
          from tpd_stage s
         where not exists (select 1 from ui.term_phenotype_display d
                            where d.tpd_term_zdb_id = s.term and d.tpd_fish_zdb_id = s.fish);

        -- association tables: wanted (tpd_id, x) pairs; drop pairs that are gone, add new ones
        create temp table pza_want on commit drop as
          select distinct d.tpd_id, g.gene from pza_stage g
            join ui.term_phenotype_display d on d.tpd_term_zdb_id = g.term and d.tpd_fish_zdb_id = g.fish;
        delete from ui.phenotype_zfin_association a
         where not exists (select 1 from pza_want w
                            where w.tpd_id = a.pza_phenotype_id and w.gene = a.pza_gene_zdb_id);
        insert into ui.phenotype_zfin_association (pza_phenotype_id, pza_gene_zdb_id)
        select w.tpd_id, w.gene from pza_want w
         where not exists (select 1 from ui.phenotype_zfin_association a
                            where a.pza_phenotype_id = w.tpd_id and a.pza_gene_zdb_id = w.gene);

        create temp table pwa_want on commit drop as
          select distinct d.tpd_id, p.warehouse_id from pwa_stage p
            join ui.term_phenotype_display d on d.tpd_term_zdb_id = p.term and d.tpd_fish_zdb_id = p.fish;
        delete from ui.phenotype_warehouse_association a
         where not exists (select 1 from pwa_want w
                            where w.tpd_id = a.pwa_phenotype_id and w.warehouse_id = a.pwa_phenotype_warehouse_id);
        insert into ui.phenotype_warehouse_association (pwa_phenotype_id, pwa_phenotype_warehouse_id)
        select w.tpd_id, w.warehouse_id from pwa_want w
         where not exists (select 1 from ui.phenotype_warehouse_association a
                            where a.pwa_phenotype_id = w.tpd_id and a.pwa_phenotype_warehouse_id = w.warehouse_id)
        """;

    /** Not used while mergesInPlace() is true; kept for a full rebuild. */
    @Override
    protected void cleanUiTables() {
        try {
            String schema = FishStatistics.class.getDeclaredField("affectedGenes").getAnnotation(JoinTable.class).schema();
            String associationTable = FishStatistics.class.getDeclaredField("affectedGenes").getAnnotation(JoinTable.class).name();
            String associationTable1 = FishStatistics.class.getDeclaredField("phenotypeStatements").getAnnotation(JoinTable.class).name();
            String fishStatsTable = FishStatistics.class.getAnnotation(Table.class).name();
            cleanoutTable(schema, associationTable, associationTable1, fishStatsTable);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }


}
