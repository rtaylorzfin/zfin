#------------------------------------------------------------------------
#
# Makefile for ZFIN_WWW eco_go_mapping
#
#
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# !! See $(TOP)/Makfile and $(TOP)/make.include for a full explanation !!
# !! of the makefile hierarchy this makefile is a part of, and of the  !!
# !! format and conventions used in this makefile.                     !!
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
#

all:
	gradle -p $(SOURCEROOT) :server_apps:data_transfer:eco_go_mapping:deployFiles


TARGETDIR = $(TARGETROOT)/server_apps/data_transfer/eco_go_mapping

GENERICS  = loadEcoGoMapping.groovy
STATICS  =  

# ---------------  Production Rules  ------------------------------------

# use default rules for directories without app pages in them

