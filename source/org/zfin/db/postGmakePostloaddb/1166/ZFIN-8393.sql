--liquibase formatted sql
--changeset rtaylor:ZFIN-8393.sql

create MATERIALIZED view solr_expression_base as
SELECT
    STRING_AGG( DISTINCT
                CASE WHEN esag_is_direct THEN
                         esag_term_name
                    END, '||') AS direct_esags,
    STRING_AGG( DISTINCT
                CASE WHEN NOT esag_is_direct THEN
                         esag_term_name
                    END, '||') AS parent_esags,
    solr_entity_expression.*
FROM
    (
        select
            'Expression' as category,
            'xpatex-' || efs_pk_id as id,
            efs_xpatex_zdb_id as xpat_zdb_id,
            fig_zdb_id as fig_zdb_id,
            fish_name as fish,
            fish_zdb_id as fish_zdb_id,
            xpatex_gene_zdb_id as gene_zdb_id,
            xpatex_assay_name as assay,
            fig_label as figure,
            '/' || fig_zdb_id as url,
            pub_date as date,
            fig_zdb_id as xref,
            pub_mini_ref as publication,
            publication.zdb_id as pub_zdb_id,
            authors as author_string,
            year(pub_date) as year,
            (select mrkr_abbrev_order from marker where mrkr_zdb_id = xpatex_gene_zdb_id) as gene_sort,
            case
                when exists (select 'x' from clone where clone_problem_type = 'Chimeric' and xpatex_probe_feature_zdb_id = clone_mrkr_zdb_id)
                    then (select mrkr_abbrev || ' expression in ' || fish_name || ' from ' || pub_mini_ref || ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_probe_feature_zdb_id)
                when xpatex_gene_zdb_id is not null and substring(xpatex_gene_zdb_id from 1 for 8) != 'ZDB-EFG'
                    then (select '<i>' || mrkr_abbrev || '</i> expression in ' || fish_name || ' from ' || pub_mini_ref || ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_gene_zdb_id)
                when xpatex_gene_zdb_id is not null and substring(xpatex_gene_zdb_id from 1 for 8) != 'ZDB-EFG'
                    then (select mrkr_abbrev || ' expression in ' || fish_name || ' from ' || pub_mini_ref || ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_gene_zdb_id)
                else (select mrkr_abbrev || ' expression in ' || fish_name || ' from ' || pub_mini_ref|| ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_atb_zdb_id)
                end as name,
            case
                when exists (select 'x' from clone where clone_problem_type = 'Chimeric' and xpatex_probe_feature_zdb_id = clone_mrkr_zdb_id)
                    then (select mrkr_abbrev || ' expression in ' || fish_name || ' from ' || pub_mini_ref || ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_probe_feature_zdb_id)
                when xpatex_gene_zdb_id is not null and substring(xpatex_gene_zdb_id from 1 for 8) != 'ZDB-EFG'
                    then (select '<i>' || mrkr_abbrev || '</i> expression in ' || fish_name || ' from ' || pub_mini_ref || ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_gene_zdb_id)
                when xpatex_gene_zdb_id is not null and substring(xpatex_gene_zdb_id from 1 for 8) != 'ZDB-EFG'
                    then (select mrkr_abbrev || ' expression in ' || fish_name || ' from ' || pub_mini_ref || ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_gene_zdb_id)
                else (select mrkr_abbrev || ' expression in ' || fish_name || ' from ' || pub_mini_ref || ' ' || nvl(fig_label,'') as full_name from marker where mrkr_zdb_id = xpatex_atb_zdb_id)
                end as full_name,
            case
                when exists (select 'x' from clone where clone_problem_type = 'Chimeric' and xpatex_probe_feature_zdb_id = clone_mrkr_zdb_id)
                    then (select mrkr_abbrev_order || ' expression in ' || fish_name_order || ' from ' || pub_mini_ref || ' ' || nvl(fig_full_label,'') as full_name from marker where mrkr_zdb_id = xpatex_probe_feature_zdb_id)
                when xpatex_gene_zdb_id is not null and substring(xpatex_gene_zdb_id from 1 for 8) != 'ZDB-EFG'
                    then (select mrkr_abbrev_order || ' expression in ' || fish_name_order || ' from ' || pub_mini_ref || ' ' || nvl(fig_full_label,'') as full_name from marker where mrkr_zdb_id = xpatex_gene_zdb_id)
                when xpatex_gene_zdb_id is not null and substring(xpatex_gene_zdb_id from 1 for 8) != 'ZDB-EFG'
                    then (select mrkr_abbrev_order || ' expression in ' || fish_name_order || ' from ' || pub_mini_ref || ' ' || nvl(fig_full_label,'') as full_name from marker where mrkr_zdb_id = xpatex_gene_zdb_id)
                else (select mrkr_abbrev_order || ' expression in ' || fish_name_order || ' from ' || pub_mini_ref|| ' ' || nvl(fig_full_label,'') as full_name from marker where mrkr_zdb_id = xpatex_atb_zdb_id)
                end as name_sort,
            to_date(get_date_from_id(efs_xpatex_zdb_id,'YYYY-MM-DD'),'%Y-%m-%d') as date2
        from expression_experiment2
                 join expression_figure_stage on efs_xpatex_zdb_id = xpatex_zdb_id
                 join fish_experiment on xpatex_genox_zdb_id = genox_zdb_id
                 join fish on genox_fish_zdb_id = fish_zdb_id
                 join figure on efs_fig_zdb_id = fig_zdb_id
                 join publication on fig_source_zdb_id = publication.zdb_id
    )
        as solr_entity_expression
        LEFT JOIN expression_search_anatomy_generated ON esag_efs_id = solr_entity_expression.id
GROUP BY
    category,
    id,
    xpat_zdb_id,
    fig_zdb_id,
    fish,
    fish_zdb_id,
    gene_zdb_id,
    assay,
    figure,
    url,
    date,
    xref,
    publication,
    pub_zdb_id,
    author_string,
    year,
    gene_sort,
    name,
    full_name,
    name_sort,
    date2 ;