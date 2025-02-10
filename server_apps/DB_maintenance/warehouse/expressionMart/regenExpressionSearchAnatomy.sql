DROP TABLE IF EXISTS expression_search_anatomy_generated_temp;
DROP TABLE IF EXISTS expression_search_anatomy_generated;

CREATE TEMP TABLE expression_search_anatomy_generated_temp AS
  SELECT
    'xpatex-' || efs1.efs_pk_id             AS esag_efs_id,
    t.term_name                             AS esag_term_name,
    atc.alltermcon_min_contain_distance = 0 AS esag_is_direct
  FROM
    expression_experiment2 xpatex1
     join
    expression_figure_stage efs1 on xpatex1.xpatex_zdb_id = efs1.efs_xpatex_zdb_id
     join
    fish_experiment genox1 on xpatex1.xpatex_genox_zdb_id = genox1.genox_zdb_id
     join
    fish fish1 on genox1.genox_fish_zdb_id = fish1.fish_zdb_id
      join
    expression_experiment2 xpatex2 on xpatex2.xpatex_gene_zdb_id = xpatex1.xpatex_gene_zdb_id AND xpatex2.xpatex_gene_zdb_id IS NOT NULL                  -- experiments must be about a gene and the same gene
     join
    expression_figure_stage efs2 on xpatex2.xpatex_zdb_id = efs2.efs_xpatex_zdb_id
     join
    fish_experiment genox2 on xpatex2.xpatex_genox_zdb_id = genox2.genox_zdb_id
     join
    fish fish2 on genox2.genox_fish_zdb_id = fish2.fish_zdb_id
     join
    expression_result2 er on efs2.efs_pk_id = er.xpatres_efs_id and AND er.xpatres_expression_found = 't'
     join
    all_term_contains atc on  (er.xpatres_superterm_zdb_id = atc.alltermcon_contained_zdb_id OR
         er.xpatres_subterm_zdb_id = atc.alltermcon_contained_zdb_id)
     join
    term t on atc.alltermcon_container_zdb_id = t.term_zdb_id
  WHERE
      TRUE
    AND (xpatex2.xpatex_genox_zdb_id = xpatex1.xpatex_genox_zdb_id OR  -- experiments are about the same fish experiment OR ...
         (genox1.genox_is_std_or_generic_control = 't' AND             -- they're both some kind of wildtype experiment
          genox2.genox_is_std_or_generic_control = 't' AND
          fish1.fish_is_wildtype = 't' AND
          fish2.fish_is_wildtype = 't' AND
          fish1.fish_functional_affected_gene_count = 0 AND
          fish2.fish_functional_affected_gene_count = 0
         )
        )
    ;

-- load distinct rows into final table. this is faster as a separate step
CREATE TABLE expression_search_anatomy_generated AS
  SELECT DISTINCT esag_efs_id, esag_term_name, esag_is_direct
  FROM expression_search_anatomy_generated_temp;

CREATE INDEX esag_efs_id_index
  ON expression_search_anatomy_generated (esag_efs_id);