#!/bin/tcsh

cd /research/zblastfiles/files/blastRegeneration/fasta/GB_daily

echo "== cp the files over from embryonix, and move old files to backup; weeklyCpGenBank.sh =="
# cp the files over from embryonix, and move old files to backup.
/research/zusers/blast/BLAST_load/target/GenBank/weeklyGB/weeklyCpGenBank.sh

echo "== merge one week's nc files into nonredundant fasta files. weeklyNrdbGenBank.sh =="
# merge one week's nc files into nonredundant fasta files.
/research/zusers/blast/BLAST_load/target/GenBank/weeklyGB/weeklyNrdbGenBank.sh

echo "== make blastdbs weeklyWudbFormatGenBank.sh =="
# make blastdbs
/research/zusers/blast/BLAST_load/target/GenBank/weeklyGB/weeklyWudbFormatGenBank.sh

# push new blastdbs to /Current
/research/zusers/blast/BLAST_load/target/GenBank/weeklyGB/weeklyPushGenBank.sh

exit 0

#=================================
# the above format automatically append the updates to the origin file and keep index
# but redundancy is not automatically eliminated. We hope the redundancy won't affect 
# the search speed very much.





