
#------------------------------------------------------------------------
#
# zfishbook data load directory
#
#
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# !! See $(TOP)/Makfile and $(TOP)/make.include for a full explanation !!
# !! of the makefile hierarchy this makefile is a part of, and of the  !!
# !! format and conventions used in this makefile.                     !!
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
#


# ---------------  Variable Definitions  --------------------------------

TOP = ../../..
include $(TOP)/make.include

TARGETDIR = $(TARGETROOT)/server_apps/data_transfer/ConstructImages
#GENERICS = zfishbook.pl preprocess_zfishbook.pl                                   
           
STATICS = loadFigures.sql images.unl constructFigure.unl

# Define targets that require special handling.

HTTP_DATA_TRANSFER_DIR = $(TARGETROOT)/home/data_transfer/ConstructImages

ENDEMICTARGETS_PRE = $(HTTP_DATA_TRANSFER_DIR)


# ---------------  Production Rules  ------------------------------------

# use default rules for directories without app pages in them

include $(TOP)/make.default.rules



# ---------------  Endemic Targets  -------------------------------------

$(HTTP_DATA_TRANSFER_DIR) :
	$(TARGET_MKDIR) $@



