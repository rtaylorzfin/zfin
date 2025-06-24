#!/bin/tcsh

cd /research/zblastfiles/files/blastRegeneration/fasta/GB_daily

echo "== Merge one week's nc files into nonredundant fasta files =="
date;

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_gb_hs.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_gb_hs.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_gb_hs.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_gb_ms.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_gb_ms.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_gb_ms.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_gb_zf.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_gb_zf.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_gb_zf.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_zf_rna.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_zf_rna.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_zf_rna.log


/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_hs_dna.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_hs_dna.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_hs_dna.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_ms_dna.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_ms_dna.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_ms_dna.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_zf_dna.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_zf_dna.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_zf_dna.log



/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_hs_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_hs_mrna.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_hs_mrna.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_ms_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_ms_mrna.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_ms_mrna.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_zf_mrna.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_zf_mrna.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_zf_mrna.log



/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_est_hs.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_est_hs.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_est_hs.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_est_ms.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_est_ms.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_est_ms.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_est_zf.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_est_zf.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_est_zf.log




/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_gss_zf.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_gss_zf.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_gss_zf.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_htg_zf.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_htg_zf.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_htg_zf.log

/opt/ab-blast/nrdb -o /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_htg_zf.fa /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc????_rna_zf.fa >& /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_rna_zf.log

date;

echo "done with weeklyNrdbGenBank.sh"

exit
