<%@ include file="/WEB-INF/jsp-include/tag-import.jsp" %>

<z:page bootstrap="true">
    <div class="login-container border mt-5 p-4 rounded">

        <h1>Reset Password</h1>

        <p>
            Enter your ZFIN username or email address and we'll send you an email with
            your username and a link to reset your password.
        </p>

        <form action="/action/profile/forgot-password" class="login-box" method="post">
            <div class="form-group">
                <label for="emailOrLogin">Username or Email Address</label>
                <input type="text" class="form-control" name="emailOrLogin" id="emailOrLogin">
            </div>

            <button type="submit" class="btn btn-primary btn-block">Submit</button>
        </form>

    </div>
</z:page>