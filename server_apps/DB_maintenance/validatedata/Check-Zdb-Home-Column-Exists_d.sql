-- Find zdb_object_type rows whose home table or home zdb-id column does not
-- exist in the database. zobjtype_home_table may be bare ('table_name') or
-- schema-qualified ('schema.table_name'); bare names default to schema 'public'.
SELECT zobjtype_name,
       zobjtype_home_table,
       zobjtype_home_zdb_id_column
FROM   zdb_object_type ot
WHERE  NOT EXISTS (
           SELECT 1
           FROM   information_schema.tables t
           WHERE  t.table_schema = CASE WHEN ot.zobjtype_home_table LIKE '%.%'
                                        THEN split_part(ot.zobjtype_home_table, '.', 1)
                                        ELSE 'public' END
             AND  t.table_name   = CASE WHEN ot.zobjtype_home_table LIKE '%.%'
                                        THEN split_part(ot.zobjtype_home_table, '.', 2)
                                        ELSE ot.zobjtype_home_table END)
   OR  NOT EXISTS (
           SELECT 1
           FROM   information_schema.columns c
           WHERE  c.table_schema = CASE WHEN ot.zobjtype_home_table LIKE '%.%'
                                        THEN split_part(ot.zobjtype_home_table, '.', 1)
                                        ELSE 'public' END
             AND  c.table_name   = CASE WHEN ot.zobjtype_home_table LIKE '%.%'
                                        THEN split_part(ot.zobjtype_home_table, '.', 2)
                                        ELSE ot.zobjtype_home_table END
             AND  c.column_name  = ot.zobjtype_home_zdb_id_column);
