Create or replace function regen_expression_term_fast_search()
  returns int as $log$

-- Incremental regen (ZFIN-10350 pattern). The wanted (xpatres, term) rows are
-- computed into a temp table and applied as a diff, counted per key because a
-- key can legitimately repeat (a term reached as both superterm and subterm
-- ancestor): surplus rows are deleted (newest ids first), changed
-- etfs_is_xpatres_term flags updated, missing rows inserted. Unchanged rows
-- keep etfs_pk_id and etfs_created_date (which now means first seen).
-- Replaces the xpatfs_temp / xpatfs_working / xpatfs_old double rename, which
-- rewrote every row on every run; plain DELETE/UPDATE/INSERT does not block
-- readers.

  begin

   -- leftovers of the old rename-swap
   drop table if exists xpatfs_temp;
   drop table if exists xpatfs_working;
   drop table if exists xpatfs_old;

   -- The swap left the live table without indexes (they stayed on xpatfs_old);
   -- their names are free once xpatfs_old is gone.
   create index if not exists expression_term_fast_search_xpatres_id_index
       on expression_term_fast_search (etfs_xpatres_pk_id);
   create index if not exists expression_term_fast_search_term_id_index
       on expression_term_fast_search (etfs_term_zdb_id);

   drop table if exists etfs_want;
   create temp table etfs_want (xpatres_pk_id int8, term text) on commit drop;

   insert into etfs_want (xpatres_pk_id, term)
		SELECT xpatres_pk_id,
					 alltermcon_container_zdb_id
		FROM   expression_result2,
					 all_term_contains,
					 expression_experiment2,
					 expression_figure_stage,
					 fish,
					 fish_experiment,
					 genotype
		WHERE  xpatres_expression_found = 't'
					 AND alltermcon_contained_zdb_id = xpatres_superterm_zdb_id
					 AND xpatex_zdb_id = efs_xpatex_zdb_id
					 AND xpatres_efs_id = efs_pk_id
					 AND xpatex_atb_zdb_id IS NOT NULL
					 AND genox_zdb_id = xpatex_genox_zdb_id
					 AND genox_is_std_or_generic_control = 't'
					 AND genox_fish_zdb_id = fish_zdb_id
					 AND fish.fish_is_wildtype = 't'
					 AND fish_genotype_zdb_id = geno_zdb_id
					 AND geno_is_wildtype = 't';

   insert into etfs_want (xpatres_pk_id, term)
					SELECT xpatres_pk_id,
								 alltermcon_container_zdb_id
					FROM   expression_result2,
								 all_term_contains,
								 expression_experiment2,
                                 expression_figure_Stage,
								 fish,
								 fish_experiment,
								 genotype
					WHERE  xpatres_expression_found = 't'
								 AND alltermcon_contained_zdb_id = xpatres_subterm_zdb_id
                                  AND xpatex_zdb_id = efs_xpatex_zdb_id
                                  AND xpatres_efs_id = efs_pk_id
								 AND xpatex_atb_zdb_id IS NOT NULL
								 AND genox_zdb_id = xpatex_genox_zdb_id
								 AND genox_is_std_or_generic_control = 't'
								 AND genox_fish_zdb_id = fish_zdb_id
								 AND fish.fish_is_wildtype = 't'
								 AND fish_genotype_zdb_id = geno_zdb_id
								 AND geno_is_wildtype = 't';

   drop table if exists etfs_want_n;
   create temp table etfs_want_n on commit drop as
     select w.xpatres_pk_id, w.term, count(*) as n,
            exists (select 'x' from expression_result2 r
                     where r.xpatres_pk_id = w.xpatres_pk_id
                       and (r.xpatres_superterm_zdb_id = w.term or r.xpatres_subterm_zdb_id = w.term)) as is_xpatres_term
       from etfs_want w
      group by w.xpatres_pk_id, w.term;
   create index on etfs_want_n (xpatres_pk_id, term);
   analyze etfs_want_n;

   -- surplus: rows beyond the wanted count for their key (all of them if the key is gone)
   delete from expression_term_fast_search
    where etfs_pk_id in (
      select h.etfs_pk_id
        from (select etfs_pk_id, etfs_xpatres_pk_id, etfs_term_zdb_id,
                     row_number() over (partition by etfs_xpatres_pk_id, etfs_term_zdb_id order by etfs_pk_id) as rn
                from expression_term_fast_search) h
        left join etfs_want_n w on w.xpatres_pk_id = h.etfs_xpatres_pk_id and w.term = h.etfs_term_zdb_id
       where h.rn > coalesce(w.n, 0));

   update expression_term_fast_search e
      set etfs_is_xpatres_term = w.is_xpatres_term
     from etfs_want_n w
    where w.xpatres_pk_id = e.etfs_xpatres_pk_id and w.term = e.etfs_term_zdb_id
      and e.etfs_is_xpatres_term is distinct from w.is_xpatres_term;

   -- missing: insert as many copies as the key is short
   insert into expression_term_fast_search (etfs_xpatres_pk_id, etfs_term_zdb_id, etfs_is_xpatres_term)
   select w.xpatres_pk_id, w.term, w.is_xpatres_term
     from etfs_want_n w
     left join (select etfs_xpatres_pk_id, etfs_term_zdb_id, count(*) as n
                  from expression_term_fast_search
                 group by etfs_xpatres_pk_id, etfs_term_zdb_id) h
            on h.etfs_xpatres_pk_id = w.xpatres_pk_id and h.etfs_term_zdb_id = w.term
     cross join lateral generate_series(1, w.n - coalesce(h.n, 0))
    where w.n > coalesce(h.n, 0);

   return 0;

  end;

$log$ LANGUAGE plpgsql
