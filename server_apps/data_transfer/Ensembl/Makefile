#------------------------------------------------------------------------
#
# Makefile for ZFIN_WWW CVS Project, downloads data from ENSEMBL to ZFIN.
#

all:
	gradle -p $(SOURCEROOT) :server_apps:data_transfer:Ensembl:deployFiles


TARGETDIR = $(TARGETROOT)/server_apps/data_transfer/Ensembl

# ---------------  Misc Targets  ----------------------------------------

# Run the download script.
# This extracts data from ENSEMBL and places it in the local DB
# does not commit the changes unless run_commit target is made

#     1 to 1 gene mapping from ensdarG to ZDB-GENE

fetch_ensdarg:
	cd $(TARGETDIR) && fetch_ensdarg.sh

load_ensdarG_rollback: fetch_ensdarg
	cd $(TARGETDIR) && cat load_ensdarG.sql rollback.sql | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 -e ${DBNAME}

load_ensdarG: fetch_ensdarg
	cd $(TARGETDIR)/ && cat load_ensdarG.sql commit.sql | ${PGBINDIR}/psql -v ON_ERROR_STOP=1 -e ${DBNAME}

# 		fetch  Ensembl GTF and convert to gff3
run_gff: 
	cd $(TARGETDIR) && fetchEnsemblGff3.groovy

run_sanger_mutant_data: $(TARGETDIR)/fetch_sangerMutantInfo.sh

# 		fetch and convert Ensembl AGP to gff3
run_agp: 
	cd $(TARGETDIR) && fetch_ensembl_agp.sh


run:        load_ensdarG run_gff run_agp

run_rollback: load_ensdarG_rollback run_gff run_agp

run_gff3:  run_gff run_agp

clean_ensembl:
	rm -f $(TARGETDIR)/load_ensdarG.sql 
	touch $(TARGETDIR)/*.sql

.PHONY += load_ensdarG load_ensdarG_commit \
	run_gff run_gff_force run_agp
