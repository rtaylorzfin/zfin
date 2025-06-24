#!/bin/tcsh
#
# Process GB release
# 
source /research/zusers/blast/BLAST_load/properties/current;

cd /research/zusers/blast/BLAST_load/target

/research/zusers/blast/BLAST_load/target/GenBank/downloadGenBank.sh
/research/zusers/blast/BLAST_load/target/GenBank/assembleGenBank.sh
/research/zusers/blast/BLAST_load/target/GenBank/convertGenBank.sh

echo "==| New release is formatted at /research/zblastfiles/files/blastRegeneration/fasta/GenBank, running: /research/zusers/blast/BLAST_load/target/GenBank/postGbRelease.sh |== If errors encountered, move /research/zblastfiles/files/blastRegeneration/fasta/Backup/ files into /research/zblastfiles/files/blastRegeneration/fasta/Current/ and figure out what is wrong before running blastdbupdate.pl again."

/research/zusers/blast/BLAST_load/target/GenBank/postGbRelease.sh

#if (watson.zfin.org == genomix.cs.uoregon.edu) then
# /research/zusers/blast/BLAST_load/target/distributeToNodesGenBank.sh
#endif

echo "==| Done with GenBank process |=="
exit
