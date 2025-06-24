#!/bin/tcsh
#
# rollback a GenBank Update
# Since GenBank is so large, we have to move symlinks around
# to try and prevent user downtime.

# rm the current fasta files
rm /research/zblastfiles/files/blastRegeneration/fasta/GenBank/*.fa

# mv the fasta files from last time back into place
mv /research/zblastfiles/files/blastRegeneration/fasta/Backup/*.fa /research/zblastfiles/files/blastRegeneration/fasta/GenBank

# swap the symlinks b/c these files are sooo big.
rm /research/zblastfiles/zmore/blastRegeneration/wu-db

# re-link to the backup for processesing
ln -s /research/zblastfiles/zmore/blastRegeneration/Backup /research/zblastfiles/zmore/blastRegeneration/wu-db

# remove the current gb blastdb
rm /research/zblastfiles/zmore/blastRegeneration/Current/gbk_*

# cp the backup to the current
cp /research/zblastfiles/zmore/blastRegeneration/Backup/gbk_* /research/zblastfiles/zmore/blastRegeneration/Current/

# swap the symlinks again.
rm /research/zblastfiles/zmore/blastRegeneration/wu-db
ln -s /research/zblastfiles/zmore/blastRegeneration/Current /research/zblastfiles/zmore/blastRegeneration/wu-db

# only to the distributeToNodes bit on Genomix
#if (watson.zfin.org == /genomix.cs.uoregon.edu) then
#    /research/zusers/blast/BLAST_load/target/GenBank/distributeToNodesGenBank.sh
#endif

exit
