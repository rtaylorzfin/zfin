package org.zfin.database;

import org.junit.Test;
import org.zfin.database.presentation.ColumnValue;
import org.zfin.database.presentation.ForeignKey;
import org.zfin.database.presentation.Table;
import org.zfin.database.presentation.TableValueLookup;
import org.zfin.util.DatabaseJdbcStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DatabaseServiceTest {

    @Test
    public void testNoJoinedJdbcStatement() {
        TableValueLookup lookup = new TableValueLookup(Table.PUBLICATION);
        String figZdbID = "ZDB-PUB-010705-7";
        ColumnValue columnValue = new ColumnValue(Table.PUBLICATION.getPkName(), figZdbID);
        lookup.addColumnValue(columnValue);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, null, true);
        assertEquals("SELECT COUNT(*) FROM publication as publication_1 WHERE publication_1.zdb_id = 'ZDB-PUB-010705-7'", statement.getQuery());

    }

    /**
     * Join from Publication with Figure
     */
    @Test
    public void testSingleJoinedJdbcStatement() {
        TableValueLookup lookup = new TableValueLookup(Table.PUBLICATION);
        String figZdbID = "ZDB-PUB-010705-7";
        ColumnValue columnValue = new ColumnValue(Table.PUBLICATION.getPkName(), figZdbID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.FIG_PUB);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) FROM publication as publication_1, figure as figure_1 " +
                     "WHERE publication_1.zdb_id = 'ZDB-PUB-010705-7' " +
                     "AND publication_1.zdb_id = figure_1.fig_source_zdb_id", statement.getQuery());

    }

    /**
     * Join TERM and ONTOLOGY_SUBSET via many-to-many table forward
     */
    @Test
    public void testManyToManyJoin() {
        TableValueLookup lookup = new TableValueLookup(Table.TERM);
        String termID = "ZDB-TERM-091209-13955";
        ColumnValue columnValue = new ColumnValue(Table.TERM.getPkName(), termID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.TERM_SUBSET_ASSOC);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) " +
                     "FROM term as term_1, term_subset as term_subset_1, ontology_subset as ontology_subset_2 " +
                     "WHERE " +
                     "term_1.term_zdb_id = 'ZDB-TERM-091209-13955' AND " +
                     "term_1.term_zdb_id = term_subset_1.termsub_term_zdb_id AND " +
                     "ontology_subset_2.osubset_pk_id = term_subset_1.termsub_subset_id", statement.getQuery());

    }

    /**
     * Join TERM and ONTOLOGY_SUBSET via many-to-many table reverse
     */
    @Test
    public void testManyToManyJoinReverse() {
        TableValueLookup lookup = new TableValueLookup(Table.ONTOLOGY_SUBSET);
        String subsetID = "1";
        ColumnValue columnValue = new ColumnValue(Table.ONTOLOGY_SUBSET.getPkName(), subsetID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.SUBSET_TERM_ASSOC);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) " +
                     "FROM ontology_subset as ontology_subset_1, term_subset as term_subset_1, term as term_2 " +
                     "WHERE " +
                     "ontology_subset_1.osubset_pk_id = '1' AND " +
                     "ontology_subset_1.osubset_pk_id = term_subset_1.termsub_subset_id AND " +
                     "term_2.term_zdb_id = term_subset_1.termsub_term_zdb_id", statement.getQuery());

    }

    /**
     * Join TERM and ONTOLOGY_SUBSET via many-to-many table reverse
     */
    @Test
    public void testDoubleManyToManyJoinReverse() {
        TableValueLookup lookup = new TableValueLookup(Table.ONTOLOGY_SUBSET);
        String subsetID = "19";
        ColumnValue columnValue = new ColumnValue(Table.ONTOLOGY_SUBSET.getPkName(), subsetID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.ONTOLOGY_SUBSET_TERM);
        foreignKeyList.add(ForeignKey.TERM_SUBSET_ASSOC);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) " +
                     "FROM ontology_subset as ontology_subset_1, term as term_1, term_subset as term_subset_1, ontology_subset as ontology_subset_2 " +
                     "WHERE " +
                     "ontology_subset_1.osubset_pk_id = '19' AND " +
                     "ontology_subset_1.osubset_pk_id = term_1.term_primary_subset_id AND " +
                     "term_1.term_zdb_id = term_subset_1.termsub_term_zdb_id AND " +
                     "ontology_subset_2.osubset_pk_id = term_subset_1.termsub_subset_id", statement.getQuery());

    }

    /**
     * Join from Publication with Figure with Image
     */
    @Test
    public void testDoubleJoinedJdbcStatement() {
        TableValueLookup lookup = new TableValueLookup(Table.PUBLICATION);
        String pubID = "ZDB-PUB-010705-7";
        ColumnValue columnValue = new ColumnValue(Table.PUBLICATION.getPkName(), pubID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.FIG_PUB, ForeignKey.IMAGE_FIGURE);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) FROM publication as publication_1, figure as figure_1, image as image_1 " +
                     "WHERE publication_1.zdb_id = 'ZDB-PUB-010705-7' " +
                     "AND publication_1.zdb_id = figure_1.fig_source_zdb_id " +
                     "AND figure_1.fig_zdb_id = image_1.img_fig_zdb_id", statement.getQuery());

        statement = DatabaseService.createQueryFromFullForeignKeyHierarchy("fig_source_zdb_id|img_fig_zdb_id", pubID, Table.PUBLICATION, null);
        assertEquals("SELECT publication_1.* FROM publication as publication_1, figure as figure_1, image as image_1 " +
                     "WHERE publication_1.zdb_id = 'ZDB-PUB-010705-7' " +
                     "AND publication_1.zdb_id = figure_1.fig_source_zdb_id " +
                     "AND figure_1.fig_zdb_id = image_1.img_fig_zdb_id", statement.getQuery());

    }

    /**
     * Join: Geno - genox - phenox - phenos
     */
    @Test
    public void testTripleJoinedJdbcStatement() {
        TableValueLookup lookup = new TableValueLookup(Table.FISH);
        String figZdbID = "ZDB-FISH-250206-6";
        ColumnValue columnValue = new ColumnValue(Table.FISH.getPkName(), figZdbID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.FISHOX_FISH, ForeignKey.PHENOX_FISHOX, ForeignKey.PHENOS_PHENOX);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) FROM fish as fish_1, fish_experiment as fish_experiment_1, phenotype_experiment as phenotype_experiment_1, " +
                     "phenotype_statement as phenotype_statement_1 WHERE fish_1.fish_zdb_id = 'ZDB-FISH-250206-6' " +
                     "AND fish_1.fish_zdb_id = fish_experiment_1.genox_fish_zdb_id " +
                     "AND fish_experiment_1.genox_zdb_id = phenotype_experiment_1.phenox_genox_zdb_id " +
                     "AND phenotype_experiment_1.phenox_pk_id = phenotype_statement_1.phenos_phenox_pk_id", statement.getQuery());

        foreignKeyList = createForeignKeyList(ForeignKey.FISHOX_FISH, ForeignKey.XPATEX_GENOX, ForeignKey.XPATEX_STAGE, ForeignKey.STAGE_XPATRES);
        statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, Table.FISH);
        assertEquals("""
                SELECT fish_1.* FROM fish as fish_1, \
                fish_experiment as fish_experiment_1, expression_experiment2 as expression_experiment2_1, \
                expression_figure_stage as expression_figure_stage_1, expression_result2 as expression_result2_1 \
                WHERE fish_1.fish_zdb_id = 'ZDB-FISH-250206-6' AND fish_1.fish_zdb_id = fish_experiment_1.genox_fish_zdb_id \
                AND fish_experiment_1.genox_zdb_id = expression_experiment2_1.xpatex_genox_zdb_id \
                AND expression_experiment2_1.xpatex_zdb_id = expression_figure_stage_1.efs_xpatex_zdb_id \
                AND expression_figure_stage_1.efs_pk_id = expression_result2_1.xpatres_efs_id"""
            , statement.getQuery());
    }

    /**
     * Join: Geno - genox - phenox - phenos - record attribution
     */
    @Test
    public void testQuadrupleJoinedJdbcStatement() {
        TableValueLookup lookup = new TableValueLookup(Table.FISH);
        String figZdbID = "ZDB-FISH-030619-2";
        ColumnValue columnValue = new ColumnValue(Table.FISH.getPkName(), figZdbID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.FISHOX_FISH, ForeignKey.XPATEX_GENOX, ForeignKey.XPATEX_STAGE, ForeignKey.STAGE_XPATRES);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("""
                SELECT COUNT(*) FROM fish as fish_1, fish_experiment as fish_experiment_1, \
                expression_experiment2 as expression_experiment2_1, expression_figure_stage as expression_figure_stage_1, \
                expression_result2 as expression_result2_1 \
                WHERE fish_1.fish_zdb_id = 'ZDB-FISH-030619-2' AND fish_1.fish_zdb_id = fish_experiment_1.genox_fish_zdb_id \
                AND fish_experiment_1.genox_zdb_id = expression_experiment2_1.xpatex_genox_zdb_id \
                AND expression_experiment2_1.xpatex_zdb_id = expression_figure_stage_1.efs_xpatex_zdb_id \
                AND expression_figure_stage_1.efs_pk_id = expression_result2_1.xpatres_efs_id"""
            , statement.getQuery());

    }

    @Test
    public void testFeatureLabAssoc() {

        TableValueLookup lookup = new TableValueLookup(Table.FEATURE);
        String featID = "ZDB-ALT-001221-2";
        ColumnValue columnValue = new ColumnValue(Table.FEATURE.getPkName(), featID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.FEATURE_LAB_ASSOC);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) " +
                     "FROM feature as feature_1, int_data_supplier as int_data_supplier_1, lab as lab_2 " +
                     "WHERE " +
                     "feature_1.feature_zdb_id = 'ZDB-ALT-001221-2' AND " +
                     "feature_1.feature_zdb_id = int_data_supplier_1.idsup_data_zdb_id AND " +
                     "lab_2.zdb_id = int_data_supplier_1.idsup_supplier_zdb_id", statement.getQuery());

    }

    @Test
    public void testLabFeatureAssoc() {

        TableValueLookup lookup = new TableValueLookup(Table.LAB);
        String featID = "ZDB-LAB-991005-53";
        ColumnValue columnValue = new ColumnValue(Table.LAB.getPkName(), featID);
        lookup.addColumnValue(columnValue);

        List<ForeignKey> foreignKeyList = createForeignKeyList(ForeignKey.LAB_FEATURE_ASSOC);
        DatabaseJdbcStatement statement = DatabaseService.createJoinJdbcStatement(lookup, foreignKeyList, true);
        assertEquals("SELECT COUNT(*) " +
                     "FROM lab as lab_1, int_data_supplier as int_data_supplier_1, feature as feature_2 " +
                     "WHERE " +
                     "lab_1.zdb_id = 'ZDB-LAB-991005-53' AND " +
                     "lab_1.zdb_id = int_data_supplier_1.idsup_supplier_zdb_id AND " +
                     "feature_2.feature_zdb_id = int_data_supplier_1.idsup_data_zdb_id", statement.getQuery());

    }

    @Test
    public void bindVariables() {
        String query = "select first 1 get_obj_name('$2') from $1";

        List<String> colList = new ArrayList<>(3);
        colList.add("ANAT");
        colList.add("FEATURE");
        colList.add("feature_id");
        String boundQuery = DatabaseService.bindVariables(query, colList);
        assertNotNull(boundQuery);
        assertEquals("select first 1 get_obj_name('feature_id') from FEATURE", boundQuery);
    }

    private List<ForeignKey> createForeignKeyList(ForeignKey... foreignKeys) {
        List<ForeignKey> foreignKeyList = new ArrayList<>(foreignKeys.length);
        Collections.addAll(foreignKeyList, foreignKeys);
        return foreignKeyList;
    }

}
