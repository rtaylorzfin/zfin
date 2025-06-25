#!/bin/bash

# Assuming this script is run from the same directory it is located in.
CURRENT_SCRIPT_DIR=$(pwd)

mkdir -p ../../fasta/GB_daily
cd ../../fasta/GB_daily

echo "== cp the files over from embryonix, and move old files to backup; weeklyCpGenBank.sh =="
# cp the files over from embryonix, and move old files to backup.
$CURRENT_SCRIPT_DIR/GenBank/weeklyGB/weeklyCpGenBank.sh

echo "== merge one week's nc files into nonredundant fasta files. weeklyNrdbGenBank.sh =="
# merge one week's nc files into nonredundant fasta files.
$CURRENT_SCRIPT_DIR/GenBank/weeklyGB/weeklyNrdbGenBank.sh

echo "== make blastdbs weeklyWudbFormatGenBank.sh =="
# make blastdbs
$CURRENT_SCRIPT_DIR/GenBank/weeklyGB/weeklyWudbFormatGenBank.sh

# push new blastdbs to /Current
$CURRENT_SCRIPT_DIR/GenBank/weeklyGB/weeklyPushGenBank.sh

exit 0

#=================================
# the above format automatically append the updates to the origin file and keep index
# but redundancy is not automatically eliminated. We hope the redundancy won't affect 
# the search speed very much.





