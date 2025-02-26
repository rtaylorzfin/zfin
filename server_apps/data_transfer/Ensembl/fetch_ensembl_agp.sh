#! /usr/bin/bash

# fetch ensembl AGP

# Makefile has a run_agp target to fire this off
# keep assembly version up to date

AGP="GRCz11.agp"

# where we put these source data files these days
gffdir="/research/zprodmore/gff3"

# where we put ZFIN gff3 tracks
dest="<!--|ROOT_PATH|-->/home/data_transfer/Downloads"

# where we start from
wrkdir="`pwd`"

cd ${wrkdir}

echo `pwd`
echo ""
echo "get DNA clones that are in ZFIN into: zfin_DNA_clone.txt"

${PGBINDIR}/psql -v ON_ERROR_STOP=1 $DBNAME < unload_zfin_DNA_clone.sql

echo "convert agp to gff3 and augment with zfin clone data as: "
echo "${dest}/E_full_zfin_clone.gff3"

# agp_to_gff3.awk ${gffdir}/${AGP} | sort -k 3  -t '=' | clone_gff3_w_alias.awk > ${dest}/E_full_zfin_clone.gff3 
sort -k6 ${gffdir}/${AGP}  | agp_to_gff3.awk > ${dest}/E_full_zfin_clone.gff3 

echo "convert agp to gff3 and augment with zfin clone data as: "
echo "${dest}/E_trim_zfin_clone.gff3"
agp_to_gff3_trim.awk  ${gffdir}/${AGP} | sort -k 3  -t '=' | clone_gff3_w_alias.awk > ${dest}/E_trim_zfin_clone.gff3







