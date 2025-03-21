package org.zfin.sequence.presentation;

import org.apache.logging.log4j.LogManager; import org.apache.logging.log4j.Logger;
import org.zfin.framework.presentation.EntityPresentation;
import org.zfin.publication.presentation.PublicationPresentation;
import org.zfin.sequence.DBLink;
import org.zfin.sequence.DisplayGroup;
import org.zfin.sequence.MarkerDBLink;
import org.zfin.sequence.TranscriptDBLink;

/**
 */
public class DBLinkPresentation extends EntityPresentation {

    private static Logger logger = LogManager.getLogger(DBLinkPresentation.class);

    /**
     * Generates an Accession link
     *
     * @param dbLink DBLink
     * @return html for marker link
     */
    public static String getLink(DBLink dbLink) {
        StringBuilder sb = new StringBuilder("");
        if (dbLink != null) {

            sb.append(dbLink.getReferenceDatabase().getForeignDB().getDbUrlPrefix());
            sb.append(dbLink.getAccessionNumber());
            if (dbLink.getReferenceDatabase().getForeignDB().getDbUrlSuffix() != null) {
                sb.append(dbLink.getReferenceDatabase().getForeignDB().getDbUrlSuffix());
            }
            String href = sb.toString();
            sb = new StringBuilder();

            sb.append(dbLink.getReferenceDatabase().getForeignDB().getDbName());
            if (false == dbLink.getReferenceDatabase().isInDisplayGroup(DisplayGroup.GroupName.MICROARRAY_EXPRESSION)) {
                if (dbLink.getReferenceDatabase().getForeignDB().getDisplayName().contains("Alliance")){

                }
                else {
                    sb.append(":");
                    sb.append((dbLink.getAccessionNumberDisplay() != null ? dbLink.getAccessionNumberDisplay() : dbLink.getAccessionNumber()));
                }
            }
            String linkText = sb.toString();
            return getHyperLink(href, linkText);
        }
        return "";
    }

    /**
     * Create an attribution link for a MarkerDBLink
     *
     * @param dblink link to attribute, ok if it has no attributions
     * @return link html
     */
    public static String getAttributionLink(MarkerDBLink dblink) {
        return getAttributionLink(dblink, dblink.getMarker().getZdbID());
    }

    public static String getAttributionLink(TranscriptDBLink dblink) {
        return getAttributionLink(dblink, dblink.getTranscript().getZdbID());
    }

    private static String getAttributionLink(DBLink dblink, String markerZdbId) {
        StringBuilder sb = new StringBuilder("");

        if (dblink.getPublicationCount() == 1) {
            sb.append(" (");
            sb.append(PublicationPresentation.getLink(dblink.getSinglePublication(), "1"));
            sb.append(")");
        } else if (dblink.getPublicationCount() > 1) {
            String count = String.valueOf(dblink.getPublicationCount());

            sb.append(" (<a href=\"/action/infrastructure/data-citation-list/");
            sb.append(dblink.getZdbID());
            sb.append("\">");
            sb.append(count);
            sb.append("</a>)");
        }

        return sb.toString();
    }


}
