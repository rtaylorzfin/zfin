package org.zfin.indexer;

import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections.CollectionUtils;
import org.zfin.framework.HibernateUtil;
import org.zfin.framework.api.Pagination;
import org.zfin.gwt.root.server.DTOConversionService;
import org.zfin.mutant.Zygosity;
import org.zfin.mutant.presentation.FishModelDisplay;
import org.zfin.ontology.GenericTerm;
import org.zfin.ontology.service.OntologyService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.zfin.repository.RepositoryFactory.getOntologyRepository;

@Log4j2
public class FishModelIndexer extends UiIndexer<FishModelDisplay> {

    public FishModelIndexer(UiIndexerConfig config) {
        super(config);
    }

    @Override
    protected List<FishModelDisplay> inputOutput() {
        List<FishModelDisplay> diseaseModelsWithFishModelsGrouped = OntologyService.getDiseaseModelsWithFishModelsGrouped(null, false, new Pagination());

        diseaseModelsWithFishModelsGrouped.forEach(fishModelDisplay -> {
            fishModelDisplay.setEvidenceSearch(fishModelDisplay.getFishModel().getDiseaseAnnotationModels().stream()
                .map(model -> DTOConversionService.evidenceCodeIdToAbbreviation(model.getDiseaseAnnotation().getEvidenceCode().getZdbID()))
                .distinct()
                .collect(Collectors.joining(",")));
            fishModelDisplay.setEvidenceCodes(new HashSet<>(fishModelDisplay.getFishModel().getDiseaseAnnotationModels().stream().map(model -> model.getDiseaseAnnotation().getEvidenceCode()).collect(Collectors.toList())));
            fishModelDisplay.setFishSearch(fishModelDisplay.getFish().getName().replaceAll("<[^>]*>", ""));
            fishModelDisplay.setConditionSearch(fishModelDisplay.getFishModel().getExperiment().getDisplayAllConditions());
        });
        diseaseModelsWithFishModelsGrouped.forEach(fishModel -> {
            int affectedGeneCount = (int) fishModel.getFish().getFishFunctionalAffectedGeneCount();
            boolean singleGenotypeFeature = fishModel.getFish().getGenotype().getGenotypeFeatures().size() == 1;
            Zygosity.Type zygosity = null;
            if (singleGenotypeFeature) {
                Zygosity zygo = fishModel.getFish().getGenotype().getGenotypeFeatures().iterator().next().getZygosity();
                switch (zygo.getType()) {
                    case HOMOZYGOUS -> zygosity = Zygosity.Type.HOMOZYGOUS;
                    case HETEROZYGOUS -> zygosity = Zygosity.Type.HETEROZYGOUS;
                    case COMPLEX -> zygosity = Zygosity.Type.COMPLEX;
                }
            }
            boolean singleSTR = fishModel.getFish().getStrList().size() == 1;
            boolean isSTR = CollectionUtils.isNotEmpty(fishModel.getFish().getStrList());
            long singleTargetGene = 10;
            if (singleSTR) {
                singleTargetGene = fishModel.getFish().getStrList().get(0).getTargetGenes().size() == 1 ? 1 : 5;
            }
            fishModel.getExperiment().isStandard();
            boolean isWildType = fishModel.getFish().getGenotype().isWildtype();
            int order = 0;
            int increment = 100;
            if (zygosity != null && zygosity.equals(Zygosity.Type.HOMOZYGOUS) && affectedGeneCount == 1 && !isSTR) {
                order = increment;
            }
            if (isWildType && singleSTR && affectedGeneCount == 1) {
                order = 2 * increment;
            }
            if (!isWildType && singleSTR && affectedGeneCount == 1) {
                order = 3 * increment;
            }
            if (zygosity != null && zygosity.equals(Zygosity.Type.HETEROZYGOUS) && affectedGeneCount == 1) {
                order = 4 * increment;
            }
            if (zygosity == null && affectedGeneCount == 1) {
                order = 5 * increment;
            }
            if (isWildType && !singleSTR && isSTR) {
                order = 6 * increment;
            }
            if (!isWildType && !singleSTR && isSTR) {
                order = 7 * increment;
            }
            if (zygosity != null && zygosity.equals(Zygosity.Type.COMPLEX)) {
                order = 8 * increment + affectedGeneCount;
            }
            if (isWildType && !fishModel.getExperiment().isStandard()) {
                order = 9 * increment;
            }
            if (!isWildType && !fishModel.getExperiment().isStandard()) {
                order = 10 * increment;
            }
            fishModel.setOrder(order);
        });
        Set<String> termIds = diseaseModelsWithFishModelsGrouped.stream()
            .filter(d -> d.getDisease() != null)
            .map(d -> d.getDisease().getZdbID())
            .collect(Collectors.toSet());
        Map<String, String[]> ancestorMap = getOntologyRepository().getAncestorTermIdsBulk(termIds);
        diseaseModelsWithFishModelsGrouped.forEach(d -> {
            if (d.getDisease() != null) {
                d.setAncestorTermIds(ancestorMap.getOrDefault(d.getDisease().getZdbID(), new String[0]));
            }
        });
        return diseaseModelsWithFishModelsGrouped;
    }

    /**
     * Merge on the natural key (fish, condition text, disease term) -- the grouping
     * key of getDiseaseModelsWithFishModelsGrouped -- instead of
     * delete-all + insert-all, so a row whose key survives keeps its zmd_id (which
     * is in API JSON) and the evidence association only changes where its content
     * does. New rows take their id from zebrafish_models_display_seq, the sequence
     * the entity's generator uses (the column default sequence lags far behind).
     */
    @Override
    protected boolean mergesInPlace() {
        return true;
    }

    @Override
    protected void mergeOutput(List<FishModelDisplay> output) {
        HibernateUtil.createTransaction();
        HibernateUtil.currentSession().doWork(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute(CREATE_STAGE);
            }
            try (PreparedStatement rows = connection.prepareStatement(
                     "insert into zmd_stage values (?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement evidence = connection.prepareStatement("insert into omea_stage values (?,?,?,?)")) {
                int n = 0;
                for (FishModelDisplay display : output) {
                    String fishox = display.getFishModel().getZdbID();
                    String term = display.getDisease() == null ? null : display.getDisease().getZdbID();
                    rows.setString(1, fishox);
                    rows.setString(2, term);
                    rows.setString(3, display.getFish() == null ? null : display.getFish().getZdbID());
                    rows.setString(4, display.getExperiment() == null ? null : display.getExperiment().getZdbID());
                    rows.setString(5, display.getSinglePublication() == null ? null : display.getSinglePublication().getZdbID());
                    rows.setInt(6, display.getNumberOfPublications());
                    rows.setString(7, display.getEvidenceSearch());
                    rows.setString(8, display.getFishSearch());
                    rows.setString(9, display.getConditionSearch());
                    rows.setObject(10, display.getOrder(), Types.INTEGER);
                    if (display.getAncestorTermIds() == null) {
                        rows.setNull(11, Types.ARRAY);
                    } else {
                        rows.setArray(11, connection.createArrayOf("text", display.getAncestorTermIds()));
                    }
                    rows.addBatch();
                    if (display.getEvidenceCodes() != null) {
                        for (GenericTerm code : display.getEvidenceCodes()) {
                            evidence.setString(1, display.getFish().getZdbID());
                            evidence.setString(2, display.getConditionSearch());
                            evidence.setString(3, term);
                            evidence.setString(4, code.getZdbID());
                            evidence.addBatch();
                        }
                    }
                    if (++n % 5000 == 0) {
                        rows.executeBatch();
                        evidence.executeBatch();
                    }
                }
                rows.executeBatch();
                evidence.executeBatch();
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
        create temp table zmd_stage (
            fishox text, term text, fish text, experiment text, pub text, pub_count int,
            evidence_search text, fish_search text, condition_search text, display_order int,
            ancestor_term_ids text[]
        ) on commit drop;
        create temp table omea_stage (fish text, condition_search text, term text, evidence text) on commit drop
        """;

    // Statements are separated by ";" + newline (see mergeOutput). Runs in the same
    // transaction as the staging, so readers never see a half-applied table.
    static final String MERGE = """
        analyze zmd_stage;
        analyze omea_stage;

        -- rows whose key is gone, and their association rows (no FK cascade)
        create temp table zmd_gone on commit drop as
          select d.zmd_id from ui.zebrafish_models_display d
           where not exists (select 1 from zmd_stage s
                              where s.fish = d.zmd_fish_zdb_id and s.condition_search = d.zmd_condition_search and s.term is not distinct from d.zmd_term_zdb_id);
        delete from ui.zebrafish_models_evidence_association where omea_zebfrafish_model_id in (select zmd_id from zmd_gone);
        delete from ui.zebrafish_models_display where zmd_id in (select zmd_id from zmd_gone);

        -- surviving keys: update only rows whose content changed (zmd_id and created_at stay)
        update ui.zebrafish_models_display d
           set zmd_fishox_zdb_id = s.fishox, zmd_experiment_zdb_id = s.experiment, zmd_pub_zdb_id = s.pub,
               zmd_pub_count = s.pub_count, zmd_evidence_search = s.evidence_search,
               zmd_fish_search = s.fish_search,
               zmd_order = s.display_order, zmd_ancestor_term_ids = s.ancestor_term_ids
          from zmd_stage s
         where s.fish = d.zmd_fish_zdb_id and s.condition_search = d.zmd_condition_search and s.term is not distinct from d.zmd_term_zdb_id
           and (d.zmd_fishox_zdb_id, d.zmd_experiment_zdb_id, d.zmd_pub_zdb_id, d.zmd_pub_count,
                d.zmd_evidence_search, d.zmd_fish_search, d.zmd_order, d.zmd_ancestor_term_ids)
               is distinct from
               (s.fishox, s.experiment, s.pub, s.pub_count, s.evidence_search, s.fish_search,
                s.display_order, s.ancestor_term_ids);

        -- new keys get a fresh zmd_id from the entity's sequence
        insert into ui.zebrafish_models_display
               (zmd_id, zmd_fishox_zdb_id, zmd_term_zdb_id, zmd_fish_zdb_id, zmd_experiment_zdb_id,
                zmd_pub_zdb_id, zmd_pub_count, zmd_evidence_search, zmd_fish_search, zmd_condition_search,
                zmd_order, zmd_ancestor_term_ids)
        select nextval('public.zebrafish_models_display_seq'), s.fishox, s.term, s.fish, s.experiment,
               s.pub, s.pub_count, s.evidence_search, s.fish_search, s.condition_search,
               s.display_order, s.ancestor_term_ids
          from zmd_stage s
         where not exists (select 1 from ui.zebrafish_models_display d
                            where d.zmd_fish_zdb_id = s.fish and d.zmd_condition_search = s.condition_search and d.zmd_term_zdb_id is not distinct from s.term);

        -- evidence association: wanted (zmd_id, evidence term) pairs; drop pairs that are gone, add new ones
        create temp table omea_want on commit drop as
          select distinct d.zmd_id, e.evidence from omea_stage e
            join ui.zebrafish_models_display d
              on d.zmd_fish_zdb_id = e.fish and d.zmd_condition_search = e.condition_search
             and d.zmd_term_zdb_id is not distinct from e.term;
        delete from ui.zebrafish_models_evidence_association a
         where not exists (select 1 from omea_want w
                            where w.zmd_id = a.omea_zebfrafish_model_id and w.evidence = a.omea_term_zdb_id);
        insert into ui.zebrafish_models_evidence_association (omea_zebfrafish_model_id, omea_term_zdb_id)
        select w.zmd_id, w.evidence from omea_want w
         where not exists (select 1 from ui.zebrafish_models_evidence_association a
                            where a.omea_zebfrafish_model_id = w.zmd_id and a.omea_term_zdb_id = w.evidence)
        """;

    /** Not used while mergesInPlace() is true; kept for a full rebuild. */
    @Override
    protected void cleanUiTables() {
        String associationTable;
        try {
            associationTable = FishModelDisplay.class.getDeclaredField("evidenceCodes").getAnnotation(JoinTable.class).name();
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        String schema = FishModelDisplay.class.getAnnotation(Table.class).schema();
        String fishModelTable = FishModelDisplay.class.getAnnotation(Table.class).name();
        cleanoutTable(schema, associationTable, fishModelTable);
    }

}
