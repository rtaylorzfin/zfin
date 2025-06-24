#!/bin/tcsh
#
# Download GB release
# 

cd /research/zblastfiles/files/blastRegeneration/fasta/

#-----------------------------
# Clean up previous log 
#-----------------------------
rm -f GenBank/xdformat*.log
mv GB/*.fa /research/zblastfiles/files/blastRegeneration/Backup
rm -rf GB/ftp.ncbi.nih.gov
rm -rf GB
mkdir GB
cd GB

#---------------------
# Download 
#---------------------
echo "==| DOWNLOAD GenBank Release |=="

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh est;

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh gss;

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh htc;

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh htg;

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh sts;

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh pri;

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh rod;

/research/zusers/blast/BLAST_load/target/GenBank/loadGBdiv.sh vrt;

#---------------------------
# Clean up intermediate data 
#----------------------------
echo "==| rm ftp file GenBank |=="
rm -rf ftp.ncbi.nih.gov


echo "==| done with GenBank download |=="

exit
