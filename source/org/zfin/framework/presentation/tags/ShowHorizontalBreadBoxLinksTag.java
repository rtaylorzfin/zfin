package org.zfin.framework.presentation.tags;


import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager; import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.zfin.search.FacetQueryEnum;
import org.zfin.search.service.FacetBuilderService;
import org.zfin.search.service.SolrService;
import org.zfin.gwt.root.util.StringUtils;
import org.zfin.util.URLCreator;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.tagext.TagSupport;
import java.io.IOException;
import java.util.Map;


public class ShowHorizontalBreadBoxLinksTag extends TagSupport {

    private QueryResponse queryResponse;
    private SolrQuery query;
    private String baseUrl;

    private Logger logger = LogManager.getLogger(ShowHorizontalBreadBoxLinksTag.class);

    private String createBreadBoxSection() {
        QueryResponse response = getQueryResponse();

        if (response == null) {
            logger.error("query response was null");
            return null;
        }

        if (response.getHeader() == null) {
            logger.error("header was null");
            return "";
        }

        if (query == null) {
            logger.error("query was null");
            return "";
        }

        //this one isn't an error state - it just means no facets are selected
        if (!SolrService.queryHasFilterQueries(query)) {
            return "";
        }

        URLCreator urlCreator = new URLCreator(baseUrl);
        String path = urlCreator.getPath();
        String q = urlCreator.getFirstValue("q");
        if (q == null) {
            q = "";
        }
        String url = path + "?q=" + q;

        StringBuilder out = new StringBuilder();
        out.append("<div class=\"col-lg-12 horizontal-breadbox\">");
        out.append("<a class=\"btn btn-outline-secondary horizontal-breadbox-label\" href=\"");
        out.append(StringEscapeUtils.escapeHtml4(url));
        out.append("\">Remove All</a>");
        for (String fq : query.getFilterQueries()) {
            out.append(getBreadBoxLinkHtml(fq));
        }

        out.append("</div>");

        return out.toString();
    }

    private String getBreadBoxLinkHtml(String fq) {
        NameValuePair nameValuePair = SolrService.splitFilterQuery(fq);
        Boolean suppressNotLabel = false;

        if (nameValuePair == null) {
            return "";
        }

        if (StringUtils.equals(nameValuePair.getName(), "root_only")) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        out.append("<a class=\"btn btn-primary horizontal-breadbox-label\" href=\"");
        out.append(SolrService.getBreadBoxUrl(fq, baseUrl));
        out.append("\">");

        //see if it's a query that's recognized and replace it with a nicer label
        FacetQueryEnum facetQueryEnum = FacetQueryEnum.getFacetQueryEnum(fq);
        if (facetQueryEnum != null) {
            nameValuePair = new BasicNameValuePair(nameValuePair.getName(), facetQueryEnum.getLabel());
            suppressNotLabel = true;
        }

        Map<String, String> dateMap = FacetBuilderService.getPublicationDateQueries();
        for (String key : dateMap.keySet()) {
            if (dateMap.get(key).equals(fq)) {
                nameValuePair = new BasicNameValuePair(nameValuePair.getName(), key);
            }
        }


        String fieldName = SolrService.getPrettyFieldName(nameValuePair.getName(), suppressNotLabel);
        String fieldValue = SolrService.getPrettyFieldValue(nameValuePair.getValue().replace("\"", ""));

        if (!StringUtils.equals(nameValuePair.getName(), "category")) {
            out.append("<span title=\"click to remove this filter\" class=\"breadbox-field-name\">");
            out.append(fieldName);
            out.append(": ");
        }
        out.append("</span>");
        out.append("<span class=\"breadbox-field-value\">");


        out.append(fieldValue);
        out.append("</span>");
        out.append("&nbsp;&times;");
        out.append("</a>");
        return out.toString();

    }

    public int doStartTag() throws JspException {

        String facetHtml = createBreadBoxSection();
        try {
            pageContext.getOut().print(facetHtml);
        } catch (IOException ioe) {
            throw new JspException("Error: IOException while writing to client" + ioe.getMessage());
        }
        return SKIP_BODY;
    }


    public int doEndTag() throws JspException {
        return Tag.EVAL_PAGE;

    }


    public QueryResponse getQueryResponse() {
        return queryResponse;
    }

    public void setQueryResponse(QueryResponse queryResponse) {
        this.queryResponse = queryResponse;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public SolrQuery getQuery() {
        return query;
    }

    public void setQuery(SolrQuery query) {
        this.query = query;
    }

    public Logger getLogger() {
        return logger;
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }
}
