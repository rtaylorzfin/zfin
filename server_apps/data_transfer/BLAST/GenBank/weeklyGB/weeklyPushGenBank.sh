#!/bin/tcsh

cd /research/zblastfiles/files/blastRegeneration/fasta/GB_daily

echo "== Switch wu-db link back to Current dir =="
rm /research/zblastfiles/zmore/blastRegeneration/wu-db
ln -s /research/zblastfiles/zmore/blastRegeneration/Current /research/zblastfiles/zmore/blastRegeneration/wu-db

# only to the distributeToNodes bit on Genomix
if (watson.zfin.org == genomix.cs.uoregon.edu) then
    /research/zusers/blast/BLAST_load/target/GenBank/distributeToNodesGenBank.sh
endif

cd /research/zblastfiles/zmore/blastRegeneration/Current ;


#===========================

echo "== Clear up the files in /research/zblastfiles/files/blastRegeneration/fasta/GB_daily == "
rm -f Last/*
mv nc*.flat Last
rm -f nc????_*_*.fa

echo "== Done with weeklyPushGenBank.sh =="

exit
