<%@ include file="/WEB-INF/jsp-include/tag-import.jsp" %>

<jsp:useBean id="image" class="org.zfin.expression.Image" scope="request"/>

<c:set var="SUMMARY" value="Image Summary"/>
<c:set var="IMAGE" value="Image"/>
<c:set var="CAPTION" value="Figure Caption"/>
<c:set var="FIGURE_DATA" value="Figure Data"/>
<c:set var="ACKNOWLEDGEMENT" value="Acknowledgments"/>

<c:set var="secs" value="${[SUMMARY, IMAGE, CAPTION, FIGURE_DATA, ACKNOWLEDGEMENT]}"/>

<z:dataPage sections="${secs}" additionalBodyClass="image">

    <jsp:attribute name="entityName">
        <div data-toggle="tooltip" data-placement="bottom" title="">
                ${image.zdbID}
        </div>
    </jsp:attribute>

    <jsp:body>

        <div id="${zfn:makeDomIdentifier(SUMMARY)}">
            <div class="small text-uppercase text-muted">${SUMMARY}</div>
            <z:attributeList>
                <z:attributeListItem label="Title">
                    <h4><a href="/${image.zdbID}">${image.zdbID}</a></h4>
                </z:attributeListItem>
                <z:attributeListItem label="Genes">
                    <c:if test="${!empty expressionGeneList}">

                             <zfin2:toggledLinkList collection="${expressionGeneList}" maxNumber="5" commaDelimited="true"/>

                    </c:if>
                </z:attributeListItem>
                <z:attributeListItem label="Source">
<%--                    <c:if test="${!empty image.figure && fn:length(image.figure.publication.figures) > 1}">--%>
                    <c:if test="${fn:length(image.figure.publication.figures) > 1}">
                            <c:set var="probeUrlPart" value=""/>
                            <c:set var="probeDisplay" value=""/>
                            <c:if test="${!empty probe}">
                                <c:set var="probeUrlPart" value="?probeZdbID=${probe.zdbID}"/>
                                <c:set var="probeDisplay" value="[${probe.abbreviation}]"/>
                            </c:if>

                            <c:if test="${image.figure.publication.type == CURATION}">
                                <c:if test="${!empty probe}">
                                    <a class="additional-figures-link" href="/action/figure/all-figure-view/${image.figure.publication.zdbID}${probeUrlPart}">All Figures for ${image.figure.publication.shortAuthorList}</a>
                                </c:if>
                            </c:if>
                            <c:if test="${image.figure.publication.type != CURATION}">
                                <a class="additional-figures-link" href="/action/figure/all-figure-view/${image.figure.publication.zdbID}${probeUrlPart}">Figures for ${image.figure.publication.shortAuthorList}${probeDisplay}</a>
                            </c:if>
                    </c:if>
                </z:attributeListItem>
            </z:attributeList>
        </div>


        <z:section title="${IMAGE}">
<%--            <zfin-figure:imageView image="${image}"/>--%>

            <div style="text-align:center; max-width:100%">
                <table border="0" cellpadding="20">
                    <tbody><tr>
                        <td align="center" bgcolor="#000000">

                            <!-- if the directLink attribute is true, link to the image source -->
                            <a href="/imageLoadUp/2019/ZDB-PUB-190215-8/ZDB-IMAGE-190624-31.png" target="_blank">
                                <img class="figure-image " src="https://zfin.org/imageLoadUp/2019/ZDB-PUB-190215-8/ZDB-IMAGE-190624-31.png">
                            </a>
                        </td>
                    </tr>
                    </tbody></table>
            </div>

        </z:section>

        <z:section title="${CAPTION}">
<%--            <h3>Figure Caption/Comments:</h3>--%>
<%--            <p><em>tmem33</em> knockdown inhibits angiogenesis and localises to the ER in ECs. <strong>a</strong>–<strong>d</strong><em>tmem33</em> is expressed ubiquitously during segmentation, but displays enrichment in the pronephros (black arrowheads) and somite boundaries, which is more pronounced from 24 hpf. Pronephric expression is evident in 26 hpf transverse sections (black arrows). <strong>e</strong>–<strong>g</strong> Tmem33-EGFP protein localises to the nuclear envelope (blue arrowheads) and ER (white arrowheads) within the caudal artery in <em>fli1a:DsRedEx2</em>embryos (Scale bars 1 µm). <strong>h</strong>–<strong>k</strong> <em>tmem33</em> morphants injected with 0.4 ng morpholinos display delayed migration of <em>Tg(fli1a:egfp)</em> positive SeAs, which stall at the horizontal myoseptum (<strong>j</strong>, <strong>k</strong>, white arrowheads), compared with control <em>Tg(fli1a:egfp)</em> positive morphants (<strong>h</strong>, <strong>i</strong>), which begin to anastomose by 30 hpf (yellow arrowheads) (scale bars 50 µm). <strong>l</strong>–<strong>o</strong> By 48 hpf, <em>Tg(fli1a:EGFP;−0.8flt1:RFP) tmem33</em> morphant SeAs complete dorsal migration, but display incomplete DLAV formation (<strong>n</strong>, <strong>o</strong>, yellow arrowheads) and lack lymphatic vasculature (red arrowheads). At 48 hpf <em>Tg(fli1a:EGFP; −0.8flt1:RFP)</em> control morphants display secondary angiogenesis (<strong>l</strong>, <strong>m</strong>, yellow arrowheads) and parachordal lymphangioblasts (red arrowhead) (scale bars 50 µm). <strong>p</strong> <em>tmem33</em> morphants injected with 0.4 ng morpholinos display reduced SeA length at 30 hpf (<em>t</em>-test ****<em>p</em> = &lt; 0.0001; <em>t</em> = 4.075; DF = 24. <em>n</em> = 3 repeats, 10 embryos per group). <strong>q</strong> <em>tmem33</em> morphants injected with 0.4 ng morpholinos display incomplete formation of DLAV (<em>t</em>-test ****<em>p</em> = &lt; 0.0001; <em>t</em> = 5.618; DF = 28. <em>n</em> = 3 repeats, 9 or 10 embryos per group). <strong>r</strong>, <strong>s</strong> Thoracic duct formation is impaired in <em>tmem33</em> morphants injected with 0.4 ng morpholinos (white asterisks), compared with control morphants (white arrowheads) (scale bars 50 µm). DA, dorsal aorta; PCV, posterior cardinal vein. Source data are provided as a <a href="https://www.nature.com/articles/s41467-019-08590-7#MOESM14" data-track="click" data-track-label="link" data-track-action="supplementary material anchor">Source Data file</a></p>--%>
            <c:if test="${!empty image.figure}">
                <zfin-figure:figureLabelAndCaption figure="${image.figure}"/>
            </c:if>
        </z:section>

        <z:section title="${FIGURE_DATA}">

                    <div style="margin-top: 1em;">
                        <a href="/ZDB-FIG-190624-36#expDetail">Expression / Labeling details</a>
                    </div>

                    <div style="margin-top: 1em;">
                        <a href="/ZDB-FIG-190624-36#phenoDetail">Phenotype details</a>
                    </div>

        </z:section>

        <z:section title="${ACKNOWLEDGEMENT}">
            <p>This image is the copyrighted work of the attributed author or publisher, and ZFIN has permission only to display this image to its users. Additional permissions should be obtained from the applicable author or publisher of the image.</p>
        </z:section>


    </jsp:body>

</z:dataPage>
