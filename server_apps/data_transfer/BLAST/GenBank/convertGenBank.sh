#!/bin/tcsh
#
# Move GB release files and then convert them to wublast dbs.
# 

cd /research/zblastfiles/files/blastRegeneration/fasta/GB

#--------------------------------
# Move final files to target dir
#---------------------------------
echo "==| Move assembled files to GenBank dir |=="

mv gbk_zf_acc.unl /research/zusers/blast/BLAST_load/target/GenBank/accession_genbank.unl

mv /research/zblastfiles/files/blastRegeneration/fasta/GB/*.fa ../GenBank

cp est/est_zf_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_est_zf.fa
cp est/est_hs_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_est_hs.fa
cp est/est_ms_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_est_ms.fa

cp /research/zblastfiles/files/blastRegeneration/fasta/GB/gbk_ms_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/
cp /research/zblastfiles/files/blastRegeneration/fasta/GB/gbk_ms_dna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/
cp /research/zblastfiles/files/blastRegeneration/fasta/GB/gbk_hs_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/
cp /research/zblastfiles/files/blastRegeneration/fasta/GB/gbk_hs_dna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/

cp /research/zblastfiles/files/blastRegeneration/fasta/GB/gbk_zf_dna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/
cp /research/zblastfiles/files/blastRegeneration/fasta/GB/gbk_zf_rna.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank/

#---------------------------------
# Database formatting
#-----------------------------------

/research/zusers/blast/BLAST_load/target/GenBank/wudbformat.sh

#----------------------
# Stamp on stamp file 
#----------------------

touch /research/zusers/blast/BLAST_load/data_transfer/GenBank/genbank.ftp;

echo "==| exit GenBank convert |=="

exit
