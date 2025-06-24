#!/bin/tcsh

cd /research/zblastfiles/files/blastRegeneration/fasta/GB_daily

echo "== Copy current genbank db to backup, and switch the wu-db link in weeklyGbUpdate.sh =="
rm /research/zblastfiles/zmore/blastRegeneration/Backup/gbk_*
cp /research/zblastfiles/zmore/blastRegeneration/Current/gbk_* /research/zblastfiles/zmore/blastRegeneration/Backup/

rm /research/zblastfiles/zmore/blastRegeneration/wu-db

# move the symlink to the Backup directory; so we don't have user downtime.
ln -s /research/zblastfiles/zmore/blastRegeneration/Backup /research/zblastfiles/zmore/blastRegeneration/wu-db

echo "== FORMAT hs_dna, ms_dna, zf_dna =="; 
/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_zf_dna.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_zf_dna /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_zf_dna.fa ;

/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_hs_dna.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_hs_dna /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_hs_dna.fa ;

/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_ms_dna.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_ms_dna /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_ms_dna.fa 

echo "== FORMAT hs_mrna, ms_mrna, zf_mrna =="; 
/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_zf_mrna.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_zf_mrna /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_zf_mrna.fa ;

/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_hs_mrna.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_hs_mrna /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_hs_mrna.fa ;

/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_ms_mrna.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_ms_mrna /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_ms_mrna.fa ;

echo "== FORMAT zf_rna =="; 
/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_zf_mrna.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_zf_rna /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_zf_rna.fa ;

echo "== FORMAT est_hs, est_ms, and est_zf =="; 
/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_est_zf.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_est_zf /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_est_zf.fa ;

/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_est_hs.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_est_hs /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_est_hs.fa ;

/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_est_ms.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_est_ms /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_est_ms.fa ;


echo "== FORMAT gss_zf, htg_zf and zf_all =="
/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_gss_zf.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_gss_zf /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_gss_zf.fa ;

/opt/ab-blast/xdformat -n -e ../GenBank/xdformat_htg_zf.log -a /research/zblastfiles/zmore/blastRegeneration/Current/gbk_htg_zf /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_htg_zf.fa ;

echo "done with weeklyWudbFormatGenBank.sh"

exit
