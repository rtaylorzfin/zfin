-- Regenerate pheno_term_fast_search incrementally (ZFIN-10350 pattern).
--
-- The wanted rows are computed into a temp table, then applied as a diff on
-- (psg_id, term, tag, is_direct_annotation): surplus rows are deleted and missing
-- ones inserted. A key can legitimately occur more than once (a term reached as
-- both e1a and e1b, or through two ancestor paths), so rows are compared by
-- count per key, not as a set. Unchanged rows keep ptfs_pk_id and their dates,
-- so ptfs_phenos_created_date now records when a row first appeared.
--
-- This replaces the rename-and-recreate swap: the whole apply is ordinary
-- DELETE/INSERT in one transaction (ROW EXCLUSIVE locks), which does not block
-- readers, and leaves no pheno_term_fast_search_old_* tables behind. It also
-- matches its FK parent, phenotype_observation_generated, which is already
-- updated incrementally, so psg_id deletes there still cascade here (the FK and
-- indexes on the live table are left as they are).

begin;

create temp table ptfs_want (
   psg_id bigint,
   term text,
   tag text,
   is_direct boolean
) on commit drop;

insert into ptfs_want
(
   psg_id,
   term,
   tag,
   is_direct
)
select
   psg_id,
   psg_e1a_zdb_id,
   psg_tag,
   't'
from
   phenotype_observation_generated
;

insert into ptfs_want
(
   psg_id,
   term,
   tag,
   is_direct
)
select
   psg_id,
   psg_e1b_zdb_id,
   psg_tag,
   't'
from
   phenotype_observation_generated
where
   psg_e1b_zdb_id is not null
;

insert into ptfs_want
(
   psg_id,
   term,
   tag,
   is_direct
)
select 
   psg_id,
   psg_e2a_zdb_id,
   psg_tag,
   't'
from
   phenotype_observation_generated
where
   psg_e2a_zdb_id is not null
;

insert into ptfs_want
(
   psg_id,
   term,
   tag,
   is_direct
)
select 
   psg_id,
   psg_e2b_zdb_id,
   psg_tag,
   't'
from
   phenotype_observation_generated
where
   psg_e2b_zdb_id is not null
;

insert into ptfs_want
(
  psg_id,
  term,
  tag
)
select 
  psg_id,
  alltermcon_container_zdb_id,
  psg_tag
from
  phenotype_observation_generated, all_term_contains
where
  psg_e1a_zdb_id = alltermcon_contained_zdb_id  
;

insert into ptfs_want
(
  psg_id,
  term,
  tag
)
select 
  psg_id,
  alltermcon_container_zdb_id,
  psg_tag
from
  phenotype_observation_generated, all_term_contains
where
  psg_e1b_zdb_id = alltermcon_contained_zdb_id  
;

insert into ptfs_want
(
  psg_id,
  term,
  tag
)
select 
  psg_id,
  alltermcon_container_zdb_id,
  psg_tag
from
  phenotype_observation_generated, all_term_contains
where
  psg_e2a_zdb_id = alltermcon_contained_zdb_id
;

insert into ptfs_want
(
  psg_id,
  term,
  tag
)
select 
  psg_id,
  alltermcon_container_zdb_id,
  psg_tag
from
  phenotype_observation_generated, all_term_contains
where
  psg_e2b_zdb_id = alltermcon_contained_zdb_id  
;

create temp table ptfs_want_n on commit drop as
  select psg_id, term, tag, is_direct, count(*) as n
    from ptfs_want
   group by psg_id, term, tag, is_direct;
create index on ptfs_want_n (psg_id, term);
analyze ptfs_want_n;

-- surplus: existing rows beyond the wanted count for their key (all of them if the
-- key is gone); the newest ids go first, so long-standing rows keep theirs
delete from pheno_term_fast_search
 where ptfs_pk_id in (
   select h.ptfs_pk_id
     from (select ptfs_pk_id, ptfs_psg_id, ptfs_term_zdb_id, ptfs_tag, ptfs_is_direct_annotation,
                  row_number() over (partition by ptfs_psg_id, ptfs_term_zdb_id, ptfs_tag, ptfs_is_direct_annotation
                                     order by ptfs_pk_id) as rn
             from pheno_term_fast_search) h
     left join ptfs_want_n w
            on w.psg_id = h.ptfs_psg_id and w.term is not distinct from h.ptfs_term_zdb_id
           and w.tag is not distinct from h.ptfs_tag
           and w.is_direct is not distinct from h.ptfs_is_direct_annotation
    where h.rn > coalesce(w.n, 0));

-- missing: insert as many copies as the key is short
insert into pheno_term_fast_search
   (ptfs_psg_id, ptfs_term_zdb_id, ptfs_tag, ptfs_is_direct_annotation, ptfs_phenos_created_date)
select w.psg_id, w.term, w.tag, w.is_direct, now()
  from ptfs_want_n w
  left join (select ptfs_psg_id, ptfs_term_zdb_id, ptfs_tag, ptfs_is_direct_annotation, count(*) as n
               from pheno_term_fast_search
              group by ptfs_psg_id, ptfs_term_zdb_id, ptfs_tag, ptfs_is_direct_annotation) h
         on h.ptfs_psg_id = w.psg_id and h.ptfs_term_zdb_id is not distinct from w.term
        and h.ptfs_tag is not distinct from w.tag
        and h.ptfs_is_direct_annotation is not distinct from w.is_direct
  cross join lateral generate_series(1, w.n - coalesce(h.n, 0))
 where w.n > coalesce(h.n, 0);

commit;
