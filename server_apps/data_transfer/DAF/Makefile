#------------------------------------------------------------------------
#
# Makefile for ZFIN_WWW CVS Project, GO Data Transfer directory
#
#
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# !! See $(TOP)/Makfile and $(TOP)/make.include for a full explanation !!
# !! of the makefile hierarchy this makefile is a part of, and of the  !!
# !! format and conventions used in this makefile.                     !!
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
#
# Targets made by this makefile are used to transfer data into or out of
# ZFIN.  They are not directly callable from the web site.


# ---------------  Variable Definitions  --------------------------------

TOP = ../../..
include $(TOP)/make.include

TARGETDIR = $(TARGETROOT)/server_apps/data_transfer/DAF

GENERICS = 
STATICS  = createDAF.sql createDAF.sh

# Define targets that require special handling.  

HTTP_DATA_TRANSFER_DIR = $(TARGETROOT)/home/data_transfer/DAF

ENDEMICTARGETS_PRE = $(HTTP_DATA_TRANSFER_DIR)

# ---------------  Production Rules  ------------------------------------

# use default rules for directories without app pages in them

include $(TOP)/make.default.rules


# ---------------  Endemic Targets  -------------------------------------

$(HTTP_DATA_TRANSFER_DIR) :
	$(TARGET_MKDIR) $@

