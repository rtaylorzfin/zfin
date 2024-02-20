package org.zfin.framework.filter;

import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Calendar;

public class CorsFilter extends OncePerRequestFilter {

    //TODO: remove this filter on PROD
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        boolean devMode = false;

        //current timestamp
        Calendar cal = Calendar.getInstance();

        Calendar cal2 = Calendar.getInstance();
        cal2.set(2024, 3, 15);//April 15

        if (cal.before(cal2)) {
            devMode = true;
        } else {
            throw new RuntimeException("CORS filter is enabled on production server. Please remove it.");
        }

        if (devMode) {
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "origin, content-type, accept, x-requested-with");
            response.addHeader("Access-Control-Max-Age", "3600");
        }
        filterChain.doFilter(request, response);
    }
}
