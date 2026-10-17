create or replace function regen_genofig_finish(vUpdate boolean, pgId int8)
returns text as $regen_genofig_finish$
 begin 
   if (vUpdate != 't') then

    insert into genotype_figure_fast_search_new(gffs_geno_zdb_id,
						gffs_fig_zdb_id,
						gffs_morph_zdb_id,
						gffs_pg_id,
						gffs_fish_zdb_id,
						gffs_psg_id,
						gffs_genox_Zdb_id)
      select distinct c.gffs_geno_zdb_id,
				c.gffs_fig_zdb_id,
				c.gffs_morph_zdb_id,
				c.gffs_pg_id,
				c.gffs_fish_zdb_id,
				c.gffs_psg_id,
				c.gffs_genox_Zdb_id 
        from genotype_Figure_fast_Search c;

 

    delete from genotype_figure_fast_search_new
      where exists (Select 'x' from regen_genofig_input_zdb_id_temp
   	 		where gffs_pg_id = rgfg_id);

   delete from genotype_figure_fast_search_new
     where gffs_psg_id not in (select psg_id from phenotype_observation_generated);

    insert into genotype_figure_fast_search_new
      (gffs_geno_zdb_id,
	gffs_fig_zdb_id,
	gffs_morph_zdb_id,
	gffs_pg_id,
	gffs_fish_zdb_id,
	gffs_psg_id,
	gffs_genox_Zdb_id)
    select distinct rgf_geno_zdb_id,
    	   rgf_fig_zdb_id,
	   rgf_morph_zdb_id,
	   rgf_pg_id,
	   rgf_fish_zdb_id,
	   rgf_psg_id,
	   rgf_genox_zdb_id
      from regen_genofig_temp;

    -- Apply genotype_figure_fast_search_new to the live table as a diff instead of
    -- swapping it in: rows are compared as whole tuples (a NULL-safe md5 of the
    -- seven columns), rows no longer wanted and repeated tuples are deleted, new
    -- tuples inserted. Unchanged rows keep gffs_serial_id and gffs_date_created,
    -- no genotype_figure_fast_search_old_* table is left behind, and the live
    -- table keeps its indexes and FKs. Plain DELETE/INSERT does not block readers.
    drop table if exists gffs_want;
    create temp table gffs_want on commit drop as
      select distinct md5(row(gffs_geno_zdb_id, gffs_fig_zdb_id, gffs_morph_zdb_id, gffs_pg_id, gffs_fish_zdb_id, gffs_psg_id, gffs_genox_zdb_id)::text) as h, gffs_geno_zdb_id, gffs_fig_zdb_id, gffs_morph_zdb_id, gffs_pg_id, gffs_fish_zdb_id, gffs_psg_id, gffs_genox_zdb_id
        from genotype_figure_fast_search_new;
    create index on gffs_want (h);

    drop table if exists gffs_have;
    create temp table gffs_have on commit drop as
      select gffs_serial_id, md5(row(gffs_geno_zdb_id, gffs_fig_zdb_id, gffs_morph_zdb_id, gffs_pg_id, gffs_fish_zdb_id, gffs_psg_id, gffs_genox_zdb_id)::text) as h
        from genotype_figure_fast_search;
    create index on gffs_have (h);
    analyze gffs_want;
    analyze gffs_have;

    delete from genotype_figure_fast_search
     where gffs_serial_id in (
       select x.gffs_serial_id
         from (select gffs_serial_id, h, row_number() over (partition by h order by gffs_serial_id) as rn
                 from gffs_have) x
        where x.rn > 1
           or not exists (select 1 from gffs_want w where w.h = x.h));

    insert into genotype_figure_fast_search (gffs_geno_zdb_id, gffs_fig_zdb_id, gffs_morph_zdb_id, gffs_pg_id, gffs_fish_zdb_id, gffs_psg_id, gffs_genox_zdb_id)
    select gffs_geno_zdb_id, gffs_fig_zdb_id, gffs_morph_zdb_id, gffs_pg_id, gffs_fish_zdb_id, gffs_psg_id, gffs_genox_zdb_id
      from gffs_want w
     where not exists (select 1 from gffs_have hv where hv.h = w.h);

    drop table genotype_figure_fast_search_new;

   else 
   	delete from genotype_figure_fast_search where gffs_pg_id = pgId;

        delete from genotype_figure_fast_search
 	       where gffs_psg_id not in (select psg_id from phenotype_observation_generated);
 
        insert into genotype_figure_fast_search
      	       (gffs_geno_zdb_id,
		gffs_fig_zdb_id,
		gffs_morph_zdb_id,
		gffs_pg_id,
		gffs_fish_zdb_id,
		gffs_psg_id,
		gffs_genox_Zdb_id)
           select distinct rgf_geno_zdb_id,
    	   	  rgf_fig_zdb_id,
	   	  rgf_morph_zdb_id,
	   	  rgf_pg_id,
	   	  rgf_fish_zdb_id,
	   	  rgf_psg_id,
	   	  rgf_genox_zdb_id
             from regen_genofig_temp
      	     where not exists (Select 'x' 
	     	       	      	      from genotype_figure_fast_Search
      	    	       	       	      where gffs_psg_id = rgf_psg_id);

  end if;

  delete from regen_genofig_temp;
  delete from regen_genofig_input_zdb_id_temp;

  return 'regen_genox() completed without error; success!';
  exception when raise_exception then
  	    return errorHint; 

end;

$regen_genofig_finish$ LANGUAGE plpgsql;
