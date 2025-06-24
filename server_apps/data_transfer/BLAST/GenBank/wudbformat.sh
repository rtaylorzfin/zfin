#!/bin/tcsh
#
# Format FASTA files from GenBank into WU BLAST dbformat. 
# -Tgb1 indicate that only format gb accession. 
#

cd /research/zblastfiles/files/blastRegeneration/fasta/GenBank
echo "Under /research/zblastfiles/files/blastRegeneration/fasta/GenBank"


echo "== FORMAT hs_dna, ms_dna, zf_dna ==";

/opt/ab-blast/xdformat -n -I -Tgb1 -t "Human DNA database" -o gbk_hs_dna -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_hs_dna.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_hs_dna.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "Mouse DNA database" -o gbk_ms_dna -e /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_ms_dna.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_ms_dna.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "Zebrafish DNA database" -o gbk_zf_dna -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_zf_dna.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_zf_dna.fa



echo "== FORMAT hs_mrna, ms_mrna, zf_mrna ==";

/opt/ab-blast/xdformat -n -I -Tgb1 -t "Human mRNA database" -o gbk_hs_mrna -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_hs_mrna.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_hs_mrna.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "Mouse mRNA database" -o gbk_ms_mrna -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_ms_mrna.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_ms_mrna.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "Zebrafish mRNA database" -o gbk_zf_mrna -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_zf_mrna.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_zf_mrna.fa



echo "== FORMAT est_hs, est_ms, and est_zf ==";

/opt/ab-blast/xdformat -n -I -Tgb1 -t "EST Human database" -o gbk_est_hs -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_est_hs.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_est_hs.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "EST Mouse database" -o gbk_est_ms -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_est_ms.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_est_ms.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "EST Zebrafish database" -o gbk_est_zf -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_est_zf.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_est_zf.fa


echo "== FORMAT gss_zf, htg_zf and zf_all (for zfin_seq retrieval) =="

/opt/ab-blast/xdformat -n -I -Tgb1 -t "GSS Zebrafish database" -o gbk_gss_zf -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_gss_zf.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_gss_zf.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "HTG Zebrafish database" -o gbk_htg_zf -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_htg_zf.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_htg_zf.fa

/opt/ab-blast/xdformat -n -I -Tgb1 -t "GenBank Zebrafish Other RNA database" -o gbk_zf_rna -e  /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat_zf_rna.log /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_zf_rna.fa




echo "== done with wublast format GenBank =="

exit
