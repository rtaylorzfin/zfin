#!/bin/bash

#cd BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily

echo "== Merge one week's nc files into nonredundant fasta files =="
date;

nrdb -o ./nc_gb_hs.fa ./nc????_gb_hs.fa >& ./merge_nc_gb_hs.log

nrdb -o ./nc_gb_ms.fa ./nc????_gb_ms.fa >& ./merge_nc_gb_ms.log

nrdb -o ./nc_gb_zf.fa ./nc????_gb_zf.fa >& ./merge_nc_gb_zf.log

nrdb -o ./nc_zf_rna.fa ./nc????_zf_rna.fa >& ./merge_nc_zf_rna.log


nrdb -o ./nc_hs_dna.fa ./nc????_hs_dna.fa >& ./merge_nc_hs_dna.log

nrdb -o ./nc_ms_dna.fa ./nc????_ms_dna.fa >& ./merge_nc_ms_dna.log

nrdb -o ./nc_zf_dna.fa ./nc????_zf_dna.fa >& ./merge_nc_zf_dna.log



nrdb -o ./nc_hs_mrna.fa ./nc????_hs_mrna.fa >& ./merge_nc_hs_mrna.log

nrdb -o ./nc_ms_mrna.fa ./nc????_ms_mrna.fa >& ./merge_nc_ms_mrna.log

nrdb -o ./nc_zf_mrna.fa ./nc????_zf_mrna.fa >& ./merge_nc_zf_mrna.log



nrdb -o ./nc_est_hs.fa ./nc????_est_hs.fa >& ./merge_nc_est_hs.log

nrdb -o ./nc_est_ms.fa ./nc????_est_ms.fa >& ./merge_nc_est_ms.log

nrdb -o ./nc_est_zf.fa ./nc????_est_zf.fa >& ./merge_nc_est_zf.log




nrdb -o ./nc_gss_zf.fa ./nc????_gss_zf.fa >& ./merge_nc_gss_zf.log

nrdb -o ./nc_htg_zf.fa ./nc????_htg_zf.fa >& ./merge_nc_htg_zf.log

nrdb -o ./nc_htg_zf.fa ./nc????_rna_zf.fa >& ./merge_nc_rna_zf.log

date;

echo "done with weeklyNrdbGenBank.sh"

exit
