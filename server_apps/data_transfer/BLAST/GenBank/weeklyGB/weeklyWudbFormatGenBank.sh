#!/bin/bash

# assuming we are in the BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily directory already
# cd BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily

source ../../config.sh

echo "== Copy current genbank db to backup, and switch the wu-db link in weeklyGbUpdate.sh =="
rm $BLAST_PATH/Backup/gbk_*
cp $BLAST_PATH/Current/gbk_* $BLAST_PATH/Backup/

rm $BLAST_PATH/wu-db

# move the symlink to the Backup directory; so we don't have user downtime.
ln -s $BLAST_PATH/Backup $BLAST_PATH/wu-db

echo "== FORMAT hs_dna, ms_dna, zf_dna =="; 
xdformat -n -e ../GenBank/xdformat_zf_dna.log -a $BLAST_PATH/Current/gbk_zf_dna ./nc_zf_dna.fa ;

xdformat -n -e ../GenBank/xdformat_hs_dna.log -a $BLAST_PATH/Current/gbk_hs_dna ./nc_hs_dna.fa ;

xdformat -n -e ../GenBank/xdformat_ms_dna.log -a $BLAST_PATH/Current/gbk_ms_dna ./nc_ms_dna.fa 

echo "== FORMAT hs_mrna, ms_mrna, zf_mrna =="; 
xdformat -n -e ../GenBank/xdformat_zf_mrna.log -a $BLAST_PATH/Current/gbk_zf_mrna ./nc_zf_mrna.fa ;

xdformat -n -e ../GenBank/xdformat_hs_mrna.log -a $BLAST_PATH/Current/gbk_hs_mrna ./nc_hs_mrna.fa ;

xdformat -n -e ../GenBank/xdformat_ms_mrna.log -a $BLAST_PATH/Current/gbk_ms_mrna ./nc_ms_mrna.fa ;

echo "== FORMAT zf_rna =="; 
xdformat -n -e ../GenBank/xdformat_zf_mrna.log -a $BLAST_PATH/Current/gbk_zf_rna ./nc_zf_rna.fa ;

echo "== FORMAT est_hs, est_ms, and est_zf =="; 
xdformat -n -e ../GenBank/xdformat_est_zf.log -a $BLAST_PATH/Current/gbk_est_zf ./nc_est_zf.fa ;

xdformat -n -e ../GenBank/xdformat_est_hs.log -a $BLAST_PATH/Current/gbk_est_hs ./nc_est_hs.fa ;

xdformat -n -e ../GenBank/xdformat_est_ms.log -a $BLAST_PATH/Current/gbk_est_ms ./nc_est_ms.fa ;


echo "== FORMAT gss_zf, htg_zf and zf_all =="
xdformat -n -e ../GenBank/xdformat_gss_zf.log -a $BLAST_PATH/Current/gbk_gss_zf ./nc_gss_zf.fa ;

xdformat -n -e ../GenBank/xdformat_htg_zf.log -a $BLAST_PATH/Current/gbk_htg_zf ./nc_htg_zf.fa ;

echo "done with weeklyWudbFormatGenBank.sh"

exit
