#!/bin/tcsh
#

rm -f /research/zblastfiles/zmore/blastRegeneration/Backup/gbk*.x*

cp /research/zblastfiles/zmore/blastRegeneration/Current/gbk*.x* /research/zblastfiles/zmore/blastRegeneration/Backup 

cp /research/zblastfiles/files/blastRegeneration/fasta/GenBank/*.fa /research/zblastfiles/files/blastRegeneration/fasta/Backup

scp /research/zfin.org/blastdb/Current/gbk*.x* /research/zblastfiles/zmore/blastRegeneration/Current

echo "finish downloading zfin_mrph.fa from embryonix and making backup of current files"

exit
