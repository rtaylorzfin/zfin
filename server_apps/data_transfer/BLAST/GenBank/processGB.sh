#!/bin/bash -e
#
# Process GB release
# 

# source /research/zusers/blast/BLAST_load/properties/current;

#cd @TARGET_PATH@

./downloadGenBank.sh
./assembleGenBank.sh
./convertGenBank.sh

echo "==| New release is formatted at .../fasta/GenBank, running: .../GenBank/postGbRelease.sh |== If errors encountered, move .../fasta/Backup/ files into .../fasta/Current/ and figure out what is wrong before running blastdbupdate.pl again."

./postGbRelease.sh

#if (@HOSTNAME@ == genomix.cs.uoregon.edu) then
# @TARGET_PATH@/distributeToNodesGenBank.sh
#endif

echo "==| Done with GenBank process |=="


exit
