#!/bin/bash

/research/zblastfiles/zmore/blastRegeneration="/private/blastdb"


for i in 001 003 004 005
do
  rsync -avz -e ssh /research/zblastfiles/zmore/blastRegeneration/Current/zfin_cdna*.x* node${i}:/research/zblastfiles/zmore/blastRegeneration/Current
done






