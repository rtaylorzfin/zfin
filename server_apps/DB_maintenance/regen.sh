#! /bin/tcsh -e 
#--------------
# Run regen functions from cron.
# Update statistics for procedures after every invocation.  This avoids 
# certain informix errors after the procedure is run.  The procedures
# themselves update statistics for the tables they generate. 

echo "Starting regen_genox at `date`"
echo 'select regen_genox(); ' | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME}

echo "Starting regen_anatomy_counts at `date`"
echo 'select regen_anatomy_counts();' | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME}

echo "Starting regen_term at `date`"
echo 'select regen_term();' | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME}

echo "Starting regen_term_indexes at `date`"
${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME} < ${SOURCEROOT}/server_apps/DB_maintenance/postgres/make_alltermcontains_indexes.sql

echo "Starting regen_expression_term_fast_search at `date`"
echo 'select regen_expression_term_fast_search();' | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME}

echo "Starting regen_clean_expression at `date`"
echo 'select regen_clean_expression();' | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME}

echo "Starting regen_fish_Components at `date`"
echo 'select regen_fish_components();' | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME}

echo "Starting regen_pheno_fast_search at `date`"
${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME} < ${TARGETROOT}/server_apps/DB_maintenance/pheno/pheno_term_regen.sql

echo "starting regenExpressionSearchAnatomy at `date`"
${PGBINDIR}/psql -v ON_ERROR_STOP=1 -d ${DBNAME} -f ${TARGETROOT}/server_apps/DB_maintenance/warehouse/expressionMart/regenExpressionSearchAnatomy.sql

echo "vacuum daily"
echo 'vacuum (analyze)' | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 ${DBNAME}


echo "Finished at `date`"
