<%@ include file="/WEB-INF/jsp-include/tag-import.jsp" %>
<%@ tag import="org.zfin.properties.ZfinPropertiesEnum" %>

<%@ attribute name="headerText" required="true" %>
<%@ attribute name="resourcesList" fragment="true" required="true" %>
<%@ attribute name="submissionForm" fragment="true" required="true" %>
<%@ attribute name="keepPrivateOption" fragment="true" required="false" %>

<div class="container-fluid">
    <h1>${headerText}</h1>

    <h3>Nomenclature Resources</h3>
    <p>
        Before proposing and reserving a name, review the current guidelines and search existing records in ZFIN for
        possible naming conflicts.
    </p>
    <ul>
        <jsp:invoke fragment="resourcesList"/>
    </ul>
    <p>
        <b>Need additional assistance? <a href="mailto:${ZfinPropertiesEnum.NOMEN_COORDINATOR.value()}">Contact the
            Nomenclature Coordinator</a></b>
    </p>

    <hr>

    <form:form action="" method="POST" cssClass="form-horizontal" modelAttribute="submission" id="nomenclature">

        <h3>Contact Information</h3>

        <div class="form-group row">
            <form:label path="name" cssClass="col-md-2 col-form-label required">Name</form:label>
            <div class="col-md-4">
                <form:input path="name" cssClass="form-control"/>
            </div>
        </div>

        <%--hidden email field, used for spam prevention--%>
        <div class="alternate-email">
            <form:label path="email" cssClass="col-md-2 col-form-label">Please leave blank</form:label>
            <div class="col-md-4">
                <form:input path="email" cssClass="form-control"/>
            </div>
        </div>

        <div class="form-group row">
            <form:label path="email2" cssClass="col-md-2 col-form-label required">Email</form:label>
            <div class="col-md-4">
                <form:input path="email2" cssClass="form-control"/>
            </div>
        </div>

        <div class="form-group row">
            <form:label path="laboratory" cssClass="col-md-2 col-form-label required">Laboratory</form:label>
            <div class="col-md-4">
                <form:input path="laboratory" cssClass="form-control"/>
            </div>
        </div>

        <jsp:invoke fragment="submissionForm"/>

        <h3>Publication Status</h3>

        <div class="form-group row">
            <div class="col-md-4">
                <c:forEach items="${pubStatusOptions}" var="option" varStatus="loop">
                    <div class="form-check">
                        <form:radiobutton path="pubStatus" value="${option}" cssClass="form-check-input" />
                        <label for="pubStatus${loop.index + 1}" class="form-check-label">${option}</label>
                    </div>
                </c:forEach>
            </div>
        </div>

        <div class="form-group row citations-group">
            <form:label path="citations" cssClass="col-md-2 col-form-label">Citations</form:label>
            <div class="col-md-6">
                <form:input path="citations" cssClass="form-control"/>
            </div>
        </div>

        <jsp:invoke fragment="keepPrivateOption" />

        <h3>Additional Comments</h3>

        <div class="form-group row">
            <div class="col-md-8">
                <form:textarea path="comments" cols="80" rows="5" cssClass="form-control"/>
            </div>
        </div>

        <button type="submit" class="btn btn-outline-secondary">Submit</button>
    </form:form>
</div>

<script>
  $(function() {

    $(".citations-group").hide();
    $(".keep-private-group").hide();
    $("input[type=radio][name=pubStatus]").change(function () {
      if (this.value === 'Published') {
        $(".citations-group label").addClass("required");
        $(".citations-group").show();
        $(".keep-private-group").hide();
      } else {
        $(".citations-group label").removeClass("required");
        $(".citations-group").show();
        $(".keep-private-group").show();
      }
    });

    $("#nomenclature").validate({
      rules: {
        name: { required: true },
        email2: {
          required: true,
          email: true
        },
        laboratory: { required: true },
        geneName: { required: true },
        geneSymbol: { required: true },
        pubStatus: { required: true },
        citations: {
          required: {
            depends: function () {
              return $('input[name=pubStatus]:checked').val() === 'Published';
            }
          }
        },
        keepPrivate: {
          required: {
            depends: function () {
              return $('input[name=pubStatus]:checked').val() !== 'Published';
            }
          }
        }
      },
      messages: {
        pubStatus: "Please select an option",
        keepPrivate: "Please select an option"
      },
      highlight: function(el) {
        $(el).closest('.form-group').addClass('has-error');
      },
      unhighlight: function(el) {
        $(el).closest('.form-group').removeClass('has-error');
      },
      errorElement: 'span',
      errorClass: 'form-text',
      errorPlacement: function (error, element) {
        if (element.is(':radio')) {
          error.prependTo(element.closest('.form-check').parent());
        } else {
          error.insertAfter(element);
        }
      }
    });

    <%-- hide as a spam prevention tactic --%>
    $(".alternate-email").hide();
  });
</script>
