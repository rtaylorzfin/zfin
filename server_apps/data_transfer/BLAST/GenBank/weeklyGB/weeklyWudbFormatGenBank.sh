#!/bin/tcsh

setenv BLASTSERVER_BLAST_DATABASE_PATH /opt/zfin/blastdb
setenv BLASTSERVER_FASTA_FILE_PATH /tmp/fasta_file_path

# Ensure the fasta directories exist
mkdir -p $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily
mkdir -p $BLASTSERVER_FASTA_FILE_PATH/fasta/GenBank

cd $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily

echo "== Copy current genbank db to backup, and switch the wu-db link in weeklyGbUpdate.sh =="
rm $BLASTSERVER_BLAST_DATABASE_PATH/Backup/gbk_*
cp $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_* $BLASTSERVER_BLAST_DATABASE_PATH/Backup/

rm $BLASTSERVER_BLAST_DATABASE_PATH/wu-db

# move the symlink to the Backup directory; so we don't have user downtime.
ln -s $BLASTSERVER_BLAST_DATABASE_PATH/Backup $BLASTSERVER_BLAST_DATABASE_PATH/wu-db

echo "== FORMAT hs_dna, ms_dna, zf_dna =="; 
xdformat -n -e ../GenBank/xdformat_zf_dna.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_zf_dna $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_zf_dna.fa ;

xdformat -n -e ../GenBank/xdformat_hs_dna.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_hs_dna $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_hs_dna.fa ;

xdformat -n -e ../GenBank/xdformat_ms_dna.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_ms_dna $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_ms_dna.fa 

echo "== FORMAT hs_mrna, ms_mrna, zf_mrna =="; 
xdformat -n -e ../GenBank/xdformat_zf_mrna.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_zf_mrna $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_zf_mrna.fa ;

xdformat -n -e ../GenBank/xdformat_hs_mrna.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_hs_mrna $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_hs_mrna.fa ;

xdformat -n -e ../GenBank/xdformat_ms_mrna.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_ms_mrna $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_ms_mrna.fa ;

echo "== FORMAT zf_rna =="; 
xdformat -n -e ../GenBank/xdformat_zf_mrna.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_zf_rna $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_zf_rna.fa ;

echo "== FORMAT est_hs, est_ms, and est_zf =="; 
xdformat -n -e ../GenBank/xdformat_est_zf.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_est_zf $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_est_zf.fa ;

xdformat -n -e ../GenBank/xdformat_est_hs.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_est_hs $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_est_hs.fa ;

xdformat -n -e ../GenBank/xdformat_est_ms.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_est_ms $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_est_ms.fa ;


echo "== FORMAT gss_zf, htg_zf and zf_all =="
xdformat -n -e ../GenBank/xdformat_gss_zf.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_gss_zf $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_gss_zf.fa ;

xdformat -n -e ../GenBank/xdformat_htg_zf.log -a $BLASTSERVER_BLAST_DATABASE_PATH/Current/gbk_htg_zf $BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily/nc_htg_zf.fa ;

echo "done with weeklyWudbFormatGenBank.sh"

exit
