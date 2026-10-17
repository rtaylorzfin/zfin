package org.zfin.indexer;

import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import lombok.extern.log4j.Log4j2;
import org.hibernate.StatelessSession;
import org.zfin.expression.Experiment;
import org.zfin.expression.ExperimentCondition;
import org.zfin.expression.Figure;
import org.zfin.framework.HibernateUtil;
import org.zfin.marker.Marker;
import org.zfin.mutant.Fish;
import org.zfin.mutant.PhenotypeStatement;
import org.zfin.mutant.PhenotypeStatementWarehouse;
import org.zfin.mutant.presentation.ChebiPhenotypeDisplay;
import org.zfin.ontology.GenericTerm;
import org.zfin.publication.Publication;
import org.zfin.repository.RepositoryFactory;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;
import static org.zfin.repository.RepositoryFactory.getOntologyRepository;

@Log4j2
public class ChebiPhenotypeIndexer extends UiIndexer<ChebiPhenotypeDisplay> {

    public ChebiPhenotypeIndexer(UiIndexerConfig config) {
        super(config);
    }

    @Override
    protected List<ChebiPhenotypeDisplay> inputOutput() {
        List<ChebiPhenotypeDisplay> resultList = getChebiPhenotypeDisplays();
        resultList.addAll(getChebiPhenotypeEQEDisplays());
        Set<String> termIds = resultList.stream()
            .filter(d -> d.getTerm() != null)
            .map(d -> d.getTerm().getZdbID())
            .collect(toSet());
        Map<String, String[]> ancestorMap = getOntologyRepository().getAncestorTermIdsBulk(termIds);
        resultList.forEach(d -> {
            if (d.getTerm() != null) {
                d.setAncestorTermIds(ancestorMap.getOrDefault(d.getTerm().getZdbID(), new String[0]));
            }
        });
        return resultList;
    }

    private static List<ChebiPhenotypeDisplay> getChebiPhenotypeDisplays() {
        Map<Fish, Map<Experiment, Map<GenericTerm, Set<PhenotypeStatementWarehouse>>>> figureMap = RepositoryFactory.getPublicationRepository().getAllChebiPhenotype();
        List<ChebiPhenotypeDisplay> resultList = new ArrayList<>();
        figureMap.forEach((fish, termMap) -> termMap.forEach((experiment, experimentMao) -> {
            experimentMao.forEach((term, phenotypeStatementWarehouses) -> {
                ChebiPhenotypeDisplay display = new ChebiPhenotypeDisplay(fish, term);
                List<PhenotypeStatementWarehouse> phenotypeStatements = (new ArrayList<>(phenotypeStatementWarehouses).stream().sorted()).toList();
                display.setPhenotypeStatements(phenotypeStatements);
                display.setExperiment(experiment);
                if (experiment.getExperimentConditions().size() > 1 &&
                    experiment.getExperimentConditions().stream().anyMatch(experimentCondition -> experimentCondition.getChebiTerm() != null)) {
                    display.setMultiChebiCondition(true);
                }
                display.setHasChebiInPhenotype(false);
                Set<String> phenotypeTags = phenotypeStatements.stream()
                    .filter(warehouse -> (warehouse.getTag().equals(PhenotypeStatement.Tag.AMELIORATED.toString()) ||
                                          warehouse.getTag().equals(PhenotypeStatement.Tag.EXACERBATED.toString())))
                    .map(PhenotypeStatementWarehouse::getTag)
                    .collect(toSet());
                if (phenotypeTags.size() > 0) {
                    display.setAmelioratedExacerbatedPhenoSearch(String.join(", ", phenotypeTags));
                }
                Set<Figure> figs = phenotypeStatementWarehouses.stream().map(warehouse -> warehouse.getPhenotypeWarehouse().getFigure()).collect(Collectors.toSet());
                display.setNumberOfFigs(figs.size());
                if (figs.size() == 1) {
                    display.setFigure(figs.iterator().next());
                }
                long imageCount = figs.stream().filter(figure -> !figure.isImgless()).map(figure -> figure.getImages().size()).count();
                if (imageCount > 0)
                    display.setHasImages(true);
                Set<Publication> pubs = phenotypeStatementWarehouses.stream().map(warehouse -> warehouse.getPhenotypeWarehouse().getFigure().getPublication()).collect(Collectors.toSet());
                display.setNumberOfPubs(pubs.size());
                if (pubs.size() == 1) {
                    display.setPublication(pubs.iterator().next());
                }
                display.setFishSearch(fish.getName().replaceAll("<[^>]*>", ""));
                display.setPhenotypeStatementSearch(phenotypeStatementWarehouses.stream().map(PhenotypeStatementWarehouse::getDisplayName).collect(Collectors.joining("|")));
                display.setGeneSymbolSearch(fish.getAffectedGenes().stream().map(Marker::getAbbreviation).sorted().collect(Collectors.joining("|")));
                display.setConditionSearch(experiment.getDisplayAllConditions());
                display.setExpConditionChebiSearch(experiment.getExperimentConditions().stream()
                    .map(ExperimentCondition::getChebiTerm)
                    .filter(Objects::nonNull)
                    .map(GenericTerm::getTermName)
                    .collect(Collectors.joining("|")));
                resultList.add(display);
            });
        }));
        return resultList;
    }

    private static List<ChebiPhenotypeDisplay> getChebiPhenotypeEQEDisplays() {
        Map<Fish, Map<Experiment, Map<GenericTerm, Set<PhenotypeStatementWarehouse>>>> figureMap = RepositoryFactory.getPublicationRepository().getAllPhenotypeWithChebiInEQE();
        List<ChebiPhenotypeDisplay> resultList = new ArrayList<>();
        figureMap.forEach((fish, termMap) -> termMap.forEach((experiment, experimentMao) -> {
            experimentMao.forEach((term, phenotypeStatementWarehouses) -> {
                ChebiPhenotypeDisplay display = new ChebiPhenotypeDisplay(fish, term);
                List<PhenotypeStatementWarehouse> phenotypeStatements = (new ArrayList<>(phenotypeStatementWarehouses).stream().sorted()).toList();
                display.setPhenotypeStatements(phenotypeStatements);
                display.setExperiment(experiment);
                if (experiment.getExperimentConditions().size() > 1 &&
                    experiment.getExperimentConditions().stream().anyMatch(experimentCondition -> experimentCondition.getChebiTerm() != null)) {
                    display.setMultiChebiCondition(true);
                }
                display.setHasChebiInPhenotype(true);
                Set<String> phenotypeTags = phenotypeStatements.stream()
                    .filter(warehouse -> (warehouse.getTag().equals(PhenotypeStatement.Tag.AMELIORATED.toString()) ||
                                          warehouse.getTag().equals(PhenotypeStatement.Tag.EXACERBATED.toString())))
                    .map(PhenotypeStatementWarehouse::getTag)
                    .collect(toSet());
                if (phenotypeTags.size() > 0) {
                    display.setAmelioratedExacerbatedPhenoSearch(String.join(", ", phenotypeTags));
                }
                Set<Figure> figs = phenotypeStatementWarehouses.stream().map(warehouse -> warehouse.getPhenotypeWarehouse().getFigure()).collect(Collectors.toSet());
                display.setNumberOfFigs(figs.size());
                if (figs.size() == 1) {
                    display.setFigure(figs.iterator().next());
                }
                long imageCount = figs.stream().filter(figure -> !figure.isImgless()).map(figure -> figure.getImages().size()).count();
                if (imageCount > 0)
                    display.setHasImages(true);
                Set<Publication> pubs = phenotypeStatementWarehouses.stream().map(warehouse -> warehouse.getPhenotypeWarehouse().getFigure().getPublication()).collect(Collectors.toSet());
                display.setNumberOfPubs(pubs.size());
                if (pubs.size() == 1) {
                    display.setPublication(pubs.iterator().next());
                }
                display.setFishSearch(fish.getName().replaceAll("<[^>]*>", ""));
                display.setPhenotypeStatementSearch(phenotypeStatementWarehouses.stream().map(PhenotypeStatementWarehouse::getDisplayName).collect(Collectors.joining("|")));
                display.setGeneSymbolSearch(fish.getAffectedGenes().stream().map(Marker::getAbbreviation).sorted().collect(Collectors.joining("|")));
                display.setConditionSearch(experiment.getDisplayAllConditions());
                display.setExpConditionChebiSearch(experiment.getExperimentConditions().stream()
                    .map(ExperimentCondition::getChebiTerm)
                    .filter(Objects::nonNull)
                    .map(GenericTerm::getTermName)
                    .collect(Collectors.joining("|")));
                resultList.add(display);
            });
        }));
        return resultList;
    }

    /**
     * Merge on the natural key (fish, experiment, term, has_chebi_in_phenotype)
     * instead of delete-all + insert-all, so a row whose key survives keeps its
     * cpd_id (which is in client URLs) and the association table only changes where
     * its content does. The term may be NULL, so it is matched through coalesce.
     */
    @Override
    protected boolean mergesInPlace() {
        return true;
    }

    @Override
    protected void mergeOutput(List<ChebiPhenotypeDisplay> output) {
        HibernateUtil.createTransaction();
        HibernateUtil.currentSession().doWork(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute(CREATE_STAGE);
            }
            try (PreparedStatement rows = connection.prepareStatement(
                     "insert into cpd_stage values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement statements = connection.prepareStatement("insert into cpwa_stage values (?,?,?,?,?)")) {
                int n = 0;
                for (ChebiPhenotypeDisplay display : output) {
                    String term = display.getTerm() == null ? null : display.getTerm().getZdbID();
                    String fish = display.getFish().getZdbID();
                    String exp = display.getExperiment() == null ? null : display.getExperiment().getZdbID();
                    rows.setString(1, term);
                    rows.setString(2, fish);
                    rows.setString(3, exp);
                    rows.setObject(4, display.getHasChebiInPhenotype(), Types.BOOLEAN);
                    rows.setString(5, display.getFigure() == null ? null : display.getFigure().getZdbID());
                    rows.setString(6, display.getPublication() == null ? null : display.getPublication().getZdbID());
                    rows.setInt(7, display.getNumberOfPubs());
                    rows.setInt(8, display.getNumberOfFigs());
                    rows.setString(9, display.getFishSearch());
                    rows.setString(10, display.getGeneSymbolSearch());
                    rows.setString(11, display.getPhenotypeStatementSearch());
                    rows.setString(12, display.getConditionSearch());
                    rows.setBoolean(13, display.isMultiChebiCondition());
                    rows.setString(14, display.getAmelioratedExacerbatedPhenoSearch());
                    rows.setString(15, display.getExpConditionChebiSearch());
                    rows.setObject(16, display.getHasImages(), Types.BOOLEAN);
                    if (display.getAncestorTermIds() == null) {
                        rows.setNull(17, Types.ARRAY);
                    } else {
                        rows.setArray(17, connection.createArrayOf("text", display.getAncestorTermIds()));
                    }
                    rows.addBatch();
                    for (PhenotypeStatementWarehouse warehouse : display.getPhenotypeStatements()) {
                        statements.setString(1, term);
                        statements.setString(2, fish);
                        statements.setString(3, exp);
                        statements.setObject(4, display.getHasChebiInPhenotype(), Types.BOOLEAN);
                        statements.setLong(5, warehouse.getId());
                        statements.addBatch();
                    }
                    if (++n % 5000 == 0) {
                        rows.executeBatch();
                        statements.executeBatch();
                    }
                }
                rows.executeBatch();
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
        create temp table cpd_stage (
            term text, fish text, exp text, has_chebi boolean, fig text, pub text, pub_count int, fig_count int,
            fish_search text, gene_search text, phenotype_search text, condition_search text,
            is_multi_chebi_condition boolean, ameliorated_exacerbated_phenotype_search text,
            exp_condition_chebi_search text, has_images boolean, ancestor_term_ids text[]
        ) on commit drop;
        create temp table cpwa_stage (term text, fish text, exp text, has_chebi boolean, warehouse_id bigint) on commit drop
        """;

    // Statements are separated by ";" + newline (see mergeOutput). Runs in the same
    // transaction as the staging, so readers never see a half-applied table.
    // cpd_is_single_misfortune is not mapped on the entity, so it is left alone (NULL on insert, as before).
    static final String MERGE = """
        analyze cpd_stage;
        analyze cpwa_stage;

        -- rows whose key is gone, and their association rows (no FK cascade)
        create temp table cpd_gone on commit drop as
          select d.cpd_id from ui.chebi_phenotype_display d
           where not exists (select 1 from cpd_stage s where @KEY(s,d)@);
        delete from ui.chebi_phenotype_warehouse_association where cpwa_phenotype_id in (select cpd_id from cpd_gone);
        delete from ui.chebi_phenotype_display where cpd_id in (select cpd_id from cpd_gone);

        -- surviving keys: update only rows whose content changed (cpd_id stays)
        update ui.chebi_phenotype_display d
           set cpd_fig_zdb_id = s.fig, cpd_pub_zdb_id = s.pub, cpd_pub_count = s.pub_count, cpd_fig_count = s.fig_count,
               cpd_fish_search = s.fish_search, cpd_gene_search = s.gene_search,
               cpd_phenotype_search = s.phenotype_search, cpd_condition_search = s.condition_search,
               cpd_is_multi_chebi_condition = s.is_multi_chebi_condition,
               cpd_ameliorated_exacerbated_phenotype_search = s.ameliorated_exacerbated_phenotype_search,
               cpd_exp_condition_chebi_search = s.exp_condition_chebi_search, cpd_has_images = s.has_images,
               cpd_ancestor_term_ids = s.ancestor_term_ids
          from cpd_stage s
         where @KEY(s,d)@
           and (d.cpd_fig_zdb_id, d.cpd_pub_zdb_id, d.cpd_pub_count, d.cpd_fig_count, d.cpd_fish_search,
                d.cpd_gene_search, d.cpd_phenotype_search, d.cpd_condition_search, d.cpd_is_multi_chebi_condition,
                d.cpd_ameliorated_exacerbated_phenotype_search, d.cpd_exp_condition_chebi_search, d.cpd_has_images,
                d.cpd_ancestor_term_ids)
               is distinct from
               (s.fig, s.pub, s.pub_count, s.fig_count, s.fish_search,
                s.gene_search, s.phenotype_search, s.condition_search, s.is_multi_chebi_condition,
                s.ameliorated_exacerbated_phenotype_search, s.exp_condition_chebi_search, s.has_images,
                s.ancestor_term_ids);

        -- new keys get a fresh cpd_id
        insert into ui.chebi_phenotype_display
               (cpd_term_zdb_id, cpd_fish_zdb_id, cpd_exp_zdb_id, cpd_has_chebi_in_phenotype, cpd_fig_zdb_id,
                cpd_pub_zdb_id, cpd_pub_count, cpd_fig_count, cpd_fish_search, cpd_gene_search, cpd_phenotype_search,
                cpd_condition_search, cpd_is_multi_chebi_condition, cpd_ameliorated_exacerbated_phenotype_search,
                cpd_exp_condition_chebi_search, cpd_has_images, cpd_ancestor_term_ids)
        select s.term, s.fish, s.exp, s.has_chebi, s.fig, s.pub, s.pub_count, s.fig_count, s.fish_search,
               s.gene_search, s.phenotype_search, s.condition_search, s.is_multi_chebi_condition,
               s.ameliorated_exacerbated_phenotype_search, s.exp_condition_chebi_search, s.has_images,
               s.ancestor_term_ids
          from cpd_stage s
         where not exists (select 1 from ui.chebi_phenotype_display d where @KEY(s,d)@);

        -- association table: wanted (cpd_id, warehouse) pairs; drop pairs that are gone
        -- and repeats of a pair (the old bag mapping left a few), add new ones
        create temp table cpwa_want on commit drop as
          select distinct d.cpd_id, p.warehouse_id from cpwa_stage p
            join ui.chebi_phenotype_display d on @KEY(p,d)@;
        delete from ui.chebi_phenotype_warehouse_association a
         where not exists (select 1 from cpwa_want w
                            where w.cpd_id = a.cpwa_phenotype_id and w.warehouse_id = a.cpwa_phenotype_warehouse_id);
        delete from ui.chebi_phenotype_warehouse_association a
         using (select ctid, row_number() over (partition by cpwa_phenotype_id, cpwa_phenotype_warehouse_id) n
                  from ui.chebi_phenotype_warehouse_association) r
         where a.ctid = r.ctid and r.n > 1;
        insert into ui.chebi_phenotype_warehouse_association (cpwa_phenotype_id, cpwa_phenotype_warehouse_id)
        select w.cpd_id, w.warehouse_id from cpwa_want w
         where not exists (select 1 from ui.chebi_phenotype_warehouse_association a
                            where a.cpwa_phenotype_id = w.cpd_id and a.cpwa_phenotype_warehouse_id = w.warehouse_id)
        """.replaceAll("@KEY\\((\\w+),(\\w+)\\)@",
            "coalesce($1.term, '') = coalesce($2.cpd_term_zdb_id, '') and $1.fish = $2.cpd_fish_zdb_id"
            + " and $1.exp = $2.cpd_exp_zdb_id and $1.has_chebi = $2.cpd_has_chebi_in_phenotype");

    /** Not used while mergesInPlace() is true; kept for a full rebuild. */
    @Override
    protected void cleanUiTables() {
        try {
            String schema = ChebiPhenotypeDisplay.class.getDeclaredField("phenotypeStatements").getAnnotation(JoinTable.class).schema();
            String associationTable = ChebiPhenotypeDisplay.class.getDeclaredField("phenotypeStatements").getAnnotation(JoinTable.class).name();
            String chebiPhenotypeTable = ChebiPhenotypeDisplay.class.getAnnotation(Table.class).name();
            cleanoutTable(schema, associationTable, chebiPhenotypeTable);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    protected void insertEntityGraph(StatelessSession session, ChebiPhenotypeDisplay entity) {
        entity.getPhenotypeStatements().forEach(session::insert);
        session.insert(entity);
    }

}
