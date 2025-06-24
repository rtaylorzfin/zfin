#!/bin/tcsh

cd /research/zblastfiles/files/blastRegeneration/fasta/GB_daily

echo "== Clean up old nc files and merge logs ==" 
rm -f /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/nc_*
rm -f /research/zblastfiles/files/blastRegeneration/fasta/GB_daily/merge_nc_*
rm -f /research/zblastfiles/files/blastRegeneration/fasta/GenBank/xdformat*.log

echo "== Scp over daily files from Embryonix =="
cp /research/zblastfiles/files/blastRegeneration/daily/nc????_*_*.fa .
cp /research/zblastfiles/files/blastRegeneration/daily/nc????.flat .

echo "done with weeklyCpGenBank.sh" 

exit
