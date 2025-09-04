--liquibase formatted sql
--changeset rtaylor:ZFIN-9845

-- Cleanup duplicate NCBI Gene IDs in db_link table

-- First get all NCBI Gene IDs that are linked to more than one ZDB gene record
SELECT
    unnest(array_agg(dblink_linked_recid)) AS zdb_id,
    dblink_acc_num AS ncbi_id,
    dblink_acc_num AS group_id,
    'many genes to one ncbi id' as reason
INTO temp TABLE ncbi_id_to_delete
FROM    db_link
WHERE    dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-1'
GROUP BY    dblink_acc_num
HAVING    count(dblink_linked_recid) > 1;

-- Now get all ZDB gene records that have more than one NCBI Gene ID
INSERT INTO ncbi_id_to_delete (
    SELECT
        dblink_linked_recid AS zdb_id,
        unnest(array_agg(dblink_acc_num)) AS ncbi_id,
        dblink_linked_recid AS group_id,
        'one gene to many ncbi ids' as reason
    FROM        db_link
    WHERE        dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-1'
    GROUP BY        dblink_linked_recid
    HAVING        count(dblink_acc_num) > 1);

-- Now delete the recent NCBI Gene IDs (from 8/25/25) for each ZDB gene record where there are issues
-- Get these by joining to db_link table
SELECT * INTO temp TABLE ncbi_id_to_delete_with_dblink
FROM
    ncbi_id_to_delete nid
    LEFT JOIN db_link ON zdb_id = dblink_linked_recid
    AND ncbi_id = dblink_acc_num
    AND dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-1'
WHERE
    dblink_zdb_id LIKE '%250825%';

DELETE FROM zdb_active_data WHERE zactvd_zdb_id IN (SELECT dblink_zdb_id FROM ncbi_id_to_delete_with_dblink);

-- These are the expected deletions (this is the view of the ncbi_id_to_delete_with_dblink table)
-- zdb_id	ncbi_id	reason	dblink_info	delete_this_record?
-- ZDB-GENE-070912-75	100150732	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-060503-70	100150732	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-161017-34	570448	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-100820-1	570448	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-070820-22	100001739	many genes to one ncbi id	uncurated: NCBI gene load 2023-07-10 18:14:22.673585-07	No
-- ZDB-GENE-030131-7635	100001739	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-110913-77	101882544	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-141212-233	101882544	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-141212-333	100151151	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-070912-117	100151151	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-120215-186	101884911	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-030131-7339	101884911	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-041210-316	792119	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-060825-105	792119	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-230622-1	110440145	many genes to one ncbi id		No
-- ZDB-GENE-220914-1	110440145	many genes to one ncbi id		No
-- ZDB-LINCRNAG-131121-11	101884646	many genes to one ncbi id	uncurated: NCBI gene load 2024-09-13 19:47:59.747003-07	No
-- ZDB-GENE-030131-8748	101884646	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-041014-55	794359	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-041014-49	794359	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-050309-44	503914	many genes to one ncbi id	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-161017-107	503914	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:02:28.824207-07	Yes
-- ZDB-GENE-070705-236	569265	many genes to one ncbi id		No
-- ZDB-GENE-130530-2	569265	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-090312-72	557378	many genes to one ncbi id	uncurated: NCBI gene load 2024-01-12 19:46:48.716629-08	No
-- ZDB-GENE-041210-279	557378	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-060526-325	100333943	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-060526-324	100333943	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-081104-208	100319064	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-030131-6962	100319064	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-070912-362	570229	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-070912-457	570229	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-131121-474	100536324	many genes to one ncbi id	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-131121-612	100536324	many genes to one ncbi id	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-191113-1	100535454	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-191113-1	137495371	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-10056	337840	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-10056	337849	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-660	321941	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-660	101885539	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-140106-138	103911794	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-140106-138	100329789	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-3514	324793	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-3514	100334815	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-100203-2	571155	one gene to many ncbi ids	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-100203-2	141375633	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-170110-1	110440078	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-170110-1	103910581	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-050809-69	606625	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-050809-69	100005092	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-7635	557454	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-7635	100001739	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-041001-198	561077	one gene to many ncbi ids	uncurated: NCBI gene load 2023-07-10 18:14:22.673585-07	No
-- ZDB-GENE-041001-198	103909278	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-9329	337383	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-9329	100004591	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-070912-352	100007758	one gene to many ncbi ids	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-070912-352	137488017	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-210112-1	100537771	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-210112-1	100329477	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-140106-66	100534721	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-140106-66	101885457	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-1967	323247	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-1967	110437841	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-060825-105	751748	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-060825-105	792119	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-3552	100536575	one gene to many ncbi ids		No
-- ZDB-GENE-030131-3552	567613	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-070912-546	794575	one gene to many ncbi ids	uncurated: NCBI gene load 2024-03-14 22:08:11.484846-07	No
-- ZDB-GENE-070912-546	137487822	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-8025	562883	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-8025	137490612	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-140106-233	101882032	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-140106-233	100535087	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-050208-733	100002776	one gene to many ncbi ids	uncurated: NCBI gene load 2023-07-10 18:14:22.673585-07	No
-- ZDB-GENE-050208-733	141380149	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-111123-2	110438237	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-111123-2	100538007	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-050506-108	553027	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-050506-108	100536119	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-060825-281	751687	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-060825-281	569678	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-141216-146	LOC101884594	one gene to many ncbi ids		No
-- ZDB-GENE-141216-146	LOC101883894	one gene to many ncbi ids		No
-- ZDB-GENE-131121-612	137488158	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-131121-612	100536324	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-041111-137	101883783	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-041111-137	141378238	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-2133	323413	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-2133	137490068	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-NCRNAG-030131-2	10053516	one gene to many ncbi ids		No
-- ZDB-NCRNAG-030131-2	100535167	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-001201-1	559475	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-001201-1	141380276	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-130503-2	101885701	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-130503-2	570938	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-041210-304	562065	one gene to many ncbi ids	uncurated: NCBI gene load 2023-07-10 18:14:22.673585-07	No
-- ZDB-GENE-041210-304	141376230	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-5439	327228	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-5439	100333807	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-060503-325	100034394	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-060503-325	137490581	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-060526-350	567416	one gene to many ncbi ids	uncurated: NCBI gene load 2024-09-13 19:47:59.747003-07	No
-- ZDB-GENE-060526-350	110439812	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-041210-279	100317818	one gene to many ncbi ids	uncurated: NCBI gene load 2024-01-12 19:46:48.716629-08	No
-- ZDB-GENE-041210-279	557378	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-130116-1	101884341	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-130116-1	101884937	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-120206-3	100332706	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-120206-3	141376900	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-200624-1	101882856	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-200624-1	141378921	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-040718-33	436616	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-040718-33	100005907	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-130213-1	100329711	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-130213-1	100334605	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-141216-82	567749	one gene to many ncbi ids	uncurated: NCBI gene load 2024-09-13 19:47:59.747003-07	No
-- ZDB-GENE-141216-82	137490405	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-7339	335399	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-7339	101884911	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-8830	100537613	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-8830	100333625	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-1076	322357	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-1076	141377165	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-080212-8	100137105	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-080212-8	100307087	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-091204-469	103911758	one gene to many ncbi ids	uncurated: NCBI gene load 2023-07-10 18:14:22.673585-07	No
-- ZDB-GENE-091204-469	141384207	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-041111-100	492526	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-041111-100	110437883	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-4539	325814	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-4539	101884850	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-050411-102	550293	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-050411-102	110437756	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-8748	336804	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-8748	101884646	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-060503-800	100034508	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-060503-800	137488446	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
-- ZDB-GENE-030131-6962	335022	one gene to many ncbi ids	uncurated: NCBI gene load 2025-05-09 19:43:14.214854-07	No
-- ZDB-GENE-030131-6962	100319064	one gene to many ncbi ids	uncurated: NCBI gene load 2025-08-25 18:48:50.287319-07	Yes
