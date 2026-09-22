-- Classify the (gene, GO) pairs lost across a cutover diff: is the pair genuinely gone, or does
-- the gene still carry a term that covers it?
--
-- ZFIN-10464. Companion to mgte_snapshot.sh / mgte_csvdiff.sh: reads the ALL-organizations
-- before/after snapshot pair those produce and writes three CSVs plus a summary.
--
-- Driven by mgte_subsumption.sh. Run by hand with:
--   cd <the -dbdiff directory> && psql -v ON_ERROR_STOP=1 -h $PGHOST -d $DBNAME \
--        -f $SOURCEROOT/server_apps/DB_maintenance/gafLoad/mgte_subsumption.sql
--
-- Every path below is RELATIVE, because \copy is a client-side meta-command that does NOT
-- interpolate psql variables -- `-v outdir=...` then `\copy ... :'outdir'/x.csv` silently
-- resolves to a file named ":" (verified). Resolving relative to psql's working directory is the
-- one mechanism that needs no string substitution, so the caller cd's into the diff directory.
--
-- WHY THIS IS NOT ALREADY IN THE DIFF
-- mgte_csvdiff.sh is a key-based set difference with no ontology awareness whatsoever, and the
-- load's own report counts flat lists of rows it acted on. Neither can tell "ZFIN no longer says
-- this" from "ZFIN says something more specific instead". Before this script the distinction was
-- made by hand: the 14,471-subsumed / 10,907-true-loss figures quoted in
-- cutover-purge-uniprot-kw2go.sql came from a query that was never committed and cannot be
-- re-run. That is the reason this exists as tooling rather than as another one-off.
--
-- THREE BUCKETS, NOT TWO
--   subsumed          the gene retains a STRICT DESCENDANT of the lost term. The statement still
--                     holds, more precisely. No loss.
--   specificity_lost  the gene retains only a STRICT ANCESTOR. ZFIN still asserts something, but
--                     less precisely than before. Partial loss -- previously invisible, and worth
--                     separating: "we kept the parent" is much weaker than "we kept a child".
--   true_loss         nothing in that lineage survives on that gene.
--
-- Pairs reproduced under a different organization or source never reach this script: at the
-- ALL_KEY level they are not lost in the first place. So these three buckets partition the loss.
--
-- ⚠️ THE CLOSURE IS BUILT FROM term_relationship, DELIBERATELY NOT all_term_contains.
-- all_term_contains is prebuilt and ~6.7M rows, which makes it the obvious thing to reach for,
-- and it is WRONG here: it also encodes `regulates` and `positively regulates`. Treating
-- "positive regulation of angiogenesis" as covering "angiogenesis" would count real losses as
-- subsumed and make the cutover look cheaper than it is. Only is_a and `part of` are transitive
-- in the sense this question needs.
--
-- EDGE DIRECTION: term_1 is the PARENT and term_2 the CHILD, verified on
-- GO:0016301 kinase activity -> GO:0019140 inositol 3-kinase activity. Descending therefore means
-- following term_1 -> term_2.

\set ON_ERROR_STOP on

create temp table mgte_before_all (
    zdb_id text, org text, marker text, gene text, term text, go_id text, go_term text,
    go_aspect text, source text, evidence text, relation text, relation_name text,
    created_by text, contributed_by text, protein_acc text, inferred_from text,
    annotation_extensions text, noctua_model text);
create temp table mgte_after_all (like mgte_before_all);

\copy mgte_before_all from 'mgte_before_ALL.csv' with (format csv, header true)
\copy mgte_after_all  from 'mgte_after_ALL.csv'  with (format csv, header true)

-- Pair identity is the ZDB marker id plus the ZDB term id, not the readable gene/go_id: a gene
-- abbreviation can change between snapshots and would then read as a loss plus an addition.
create temp table pair_before as select distinct marker, term from mgte_before_all;
create temp table pair_after  as select distinct marker, term from mgte_after_all;

create temp table pair_lost as
select b.marker, b.term from pair_before b
 left join pair_after a on a.marker = b.marker and a.term = b.term
 where a.marker is null;
create index on pair_lost (marker);
create index on pair_lost (term);

-- Only the terms that can matter: lost terms (the ancestors we descend FROM) and terms still held
-- by a gene that lost something (the candidate descendants). Closing over all of GO would be
-- pointless work.
create temp table kept_term as
select distinct a.marker, a.term
  from pair_after a
 where exists (select 1 from pair_lost l where l.marker = a.marker);
create index on kept_term (marker);
create index on kept_term (term);

-- Strict descendants of each lost term, over is_a + `part of` only. UNION (not UNION ALL) in the
-- recursive term collapses the many redundant paths a DAG produces; without it this does not
-- terminate usefully on GO.
create temp table descendant as
with recursive d(root, node) as (
    select distinct l.term, l.term from pair_lost l
  union
    select d.root, tr.termrel_term_2_zdb_id
      from d
      join term_relationship tr
        on tr.termrel_term_1_zdb_id = d.node
       and tr.termrel_type in ('is_a', 'part of')
)
select root, node from d where root <> node;   -- STRICT: drop the distance-0 self pair
create index on descendant (root, node);

-- Strict ancestors, the same walk in reverse.
create temp table ancestor as
with recursive u(root, node) as (
    select distinct l.term, l.term from pair_lost l
  union
    select u.root, tr.termrel_term_1_zdb_id
      from u
      join term_relationship tr
        on tr.termrel_term_2_zdb_id = u.node
       and tr.termrel_type in ('is_a', 'part of')
)
select root, node from u where root <> node;
create index on ancestor (root, node);

-- Subsumed wins over specificity_lost when both hold: a gene can keep a child AND a parent of the
-- lost term, and keeping the child is the stronger statement.
create temp table classified as
select l.marker, l.term,
       case when exists (select 1 from kept_term k join descendant d on d.node = k.term
                          where k.marker = l.marker and d.root = l.term) then 'subsumed'
            when exists (select 1 from kept_term k join ancestor a on a.node = k.term
                          where k.marker = l.marker and a.root = l.term) then 'specificity_lost'
            else 'true_loss' end as bucket
  from pair_lost l;

\echo ''
\echo '=== lost (gene, GO) pairs by bucket ==='
select bucket, count(*) as pairs,
       round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 1) as pct
  from classified group by 1 order by 2 desc;

\echo ''
\echo '=== true loss by owning organization (as held BEFORE) ==='
select b.org, count(distinct (c.marker, c.term)) as pairs
  from classified c join mgte_before_all b on b.marker = c.marker and b.term = c.term
 where c.bucket = 'true_loss' group by 1 order by 2 desc;

\echo ''
\echo '=== true loss by source publication ==='
select b.source, count(distinct (c.marker, c.term)) as pairs
  from classified c join mgte_before_all b on b.marker = c.marker and b.term = c.term
 where c.bucket = 'true_loss' group by 1 order by 2 desc limit 15;

-- One row per lost pair, with the readable columns and, where one exists, an example of the term
-- that covers it -- so a curator can check the verdict rather than trust it.
create temp table detail as
select c.bucket, b.org, b.gene, b.go_id, b.go_term, b.go_aspect, b.source, b.evidence,
       kt.term_ont_id as covering_go_id, kt.term_name as covering_go_term
  from classified c
  join lateral (select * from mgte_before_all m
                 where m.marker = c.marker and m.term = c.term limit 1) b on true
  left join lateral (
        select t.term_ont_id, t.term_name
          from kept_term k
          join descendant d on d.node = k.term and d.root = c.term
          join term t on t.term_zdb_id = k.term
         where k.marker = c.marker and c.bucket = 'subsumed'
      union all
        select t.term_ont_id, t.term_name
          from kept_term k
          join ancestor a on a.node = k.term and a.root = c.term
          join term t on t.term_zdb_id = k.term
         where k.marker = c.marker and c.bucket = 'specificity_lost'
         limit 1) kt on true;

\copy (select * from detail where bucket = 'true_loss'        order by org, gene, go_id) to 'mgte_subsumption_true_loss.csv'        with (format csv, header true)
\copy (select * from detail where bucket = 'subsumed'         order by org, gene, go_id) to 'mgte_subsumption_subsumed.csv'         with (format csv, header true)
\copy (select * from detail where bucket = 'specificity_lost' order by org, gene, go_id) to 'mgte_subsumption_specificity_lost.csv' with (format csv, header true)
