begin work ;

-- ZFIN-10464: loads GO's DERIVED ECO->GO mapping (1,422 rows) rather than the flat one (26).
-- getECOGOMapping.groovy writes gafeco.txt as evcode,ecoterm,isdefault.

create temp table tmp_eco_map (evcode text, ecoterm text, isdefault text);

\copy tmp_eco_map from '<!--|TARGETROOT|-->/server_apps/data_transfer/eco_go_mapping/gafeco.txt' delimiter ',';

-- Only ECO terms that have NO mapping at all are candidates.
--
-- This is the rule that makes switching to the 1,422-row derived file safe: an ECO term that
-- already resolves keeps resolving to exactly what it resolved to before, so no stored
-- annotation's three-letter evidence code can change underneath us, and the curated mappings
-- that are not in GO's file (postGmakePostloaddb DLOAD-672, ZFIN-9426) are untouched. The
-- derived file can only fill gaps. Without it the file would add a SECOND code for
-- ECO:0000031 (ISS + ISA) and ECO:0000262 (curated ISS + ISM), which is both a silent
-- reinterpretation and a lookup hazard.
create temp table tmp_candidate as
select t.term_zdb_id,
       m.evcode,
       (m.isdefault = 'Default') as is_default
  from term t
  join tmp_eco_map m on t.term_ont_id = m.ecoterm
 where not exists (select 1 from eco_go_mapping e where e.egm_term_zdb_id = t.term_zdb_id);

-- A term the file gives several codes for, with no single "Default" to choose between them, is
-- skipped rather than guessed at. ECO:0007295 (EXP + IEA, neither marked) is the only one today.
-- Skipping leaves it unmapped, which surfaces as a visible "invalid eco code" in a load that
-- needs it -- far better than silently picking one and quietly mislabelling every such row.
create temp table tmp_ambiguous as
select term_zdb_id
  from tmp_candidate
 group by term_zdb_id
having count(distinct evcode) > 1
   and count(*) filter (where is_default) <> 1;

\echo ''
\echo '=== ECO terms mapped to several GO codes with no Default to choose between -- SKIPPED ==='
select t.term_ont_id, string_agg(c.evcode, ', ' order by c.evcode) as codes
  from tmp_candidate c
  join term t on t.term_zdb_id = c.term_zdb_id
 where c.term_zdb_id in (select term_zdb_id from tmp_ambiguous)
 group by 1 order by 1;

-- DISTINCT ON with is_default first takes the equivalence mapping when the file marks one; for
-- the overwhelming majority the term has a single code and the ordering is irrelevant. evcode is
-- the final tiebreak purely so the statement is deterministic.
--
-- on conflict is kept for re-runnability: it is unreachable given the not-exists above, but this
-- load is re-run routinely and a unique-violation abort would take the whole transaction.
insert into eco_go_mapping (egm_term_zdb_id, egm_go_evidence_code)
select distinct on (c.term_zdb_id) c.term_zdb_id, c.evcode
  from tmp_candidate c
 where c.term_zdb_id not in (select term_zdb_id from tmp_ambiguous)
 order by c.term_zdb_id, c.is_default desc, c.evcode
on conflict (egm_term_zdb_id, egm_go_evidence_code) do nothing;

\echo ''
\echo '=== eco_go_mapping totals ==='
select count(*) as mappings,
       count(distinct egm_term_zdb_id) as distinct_eco_terms
  from eco_go_mapping;

commit work;
