package io.opentracing.contrib.web.servlet.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.opentracing.Scope;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

/**
 * Tracing servlet filter.
 *
 * Filter can be programmatically added to {@link ServletContext} or initialized
 * via web.xml.
 *
 * Following code examples show possible initialization:
 *
 * <pre>
 * {
 *     &#64;code
 *     TracingFilter filter = new TracingFilter(tracer);
 *     servletContext.addFilter("tracingFilter", filter);
 * }
 * </pre>
 *
 * Or include filter in web.xml and:
 * 
 * <pre>
 * {@code
 *  GlobalTracer.register(tracer);
 *  servletContext.setAttribute({@link TracingFilter#SPAN_DECORATORS}, listOfDecorators); // optional, if no present ServletFilterSpanDecorator.STANDARD_TAGS is applied
 * }
 * </pre>
 *
 * Current server span context is accessible via
 * {@link HttpServletRequest#getAttribute(String)} with name
 * {@link TracingFilter#SERVER_SPAN_CONTEXT}.
 *
 * @author Pavol Loffay
 */
public class TracingFilter implements Filter {
    private static final Logger log = Logger.getLogger(TracingFilter.class.getName());

    /**
     * Use as a key of {@link ServletContext#setAttribute(String, Object)} to set
     * span decorators
     */
    public static final String SPAN_DECORATORS = TracingFilter.class.getName() + ".spanDecorators";
    /**
     * Use as a key of {@link ServletContext#setAttribute(String, Object)} to skip
     * pattern
     */
    public static final String SKIP_PATTERN = TracingFilter.class.getName() + ".skipPattern";

    /**
     * Used as a key of {@link HttpServletRequest#setAttribute(String, Object)} to
     * inject server span context
     */
    public static final String SERVER_SPAN_CONTEXT = TracingFilter.class.getName() + ".activeSpanContext";

    private FilterConfig filterConfig;

    protected Tracer tracer;
    private List<ServletFilterSpanDecorator> spanDecorators;
    private Pattern skipPattern;

    /**
     * Tracer instance has to be registered with
     * {@link GlobalTracer#register(Tracer)}.
     */
    public TracingFilter() {
        this(GlobalTracer.get());
    }

    /**
     * @param tracer
     */
    public TracingFilter(Tracer tracer) {
        this(tracer, Collections.singletonList(ServletFilterSpanDecorator.STANDARD_TAGS), null);
    }

    /**
     *
     * @param tracer         tracer
     * @param spanDecorators decorators
     * @param skipPattern    null or pattern to exclude certain paths from tracing
     *                       e.g. "/health"
     */
    public TracingFilter(Tracer tracer, List<ServletFilterSpanDecorator> spanDecorators, Pattern skipPattern) {
        this.tracer = tracer;
        this.spanDecorators = new ArrayList<>(spanDecorators);
        this.spanDecorators.removeAll(Collections.singleton(null));
        this.skipPattern = skipPattern;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
        ServletContext servletContext = filterConfig.getServletContext();

        // use decorators from context attributes
        Object contextAttribute = servletContext.getAttribute(SPAN_DECORATORS);
        if (contextAttribute instanceof Collection) {
            List<ServletFilterSpanDecorator> decorators = new ArrayList<>();
            for (Object decorator : (Collection) contextAttribute) {
                if (decorator instanceof ServletFilterSpanDecorator) {
                    decorators.add((ServletFilterSpanDecorator) decorator);
                } else {
                    log.severe(decorator + " is not an instance of " + ServletFilterSpanDecorator.class);
                }
            }
            this.spanDecorators = decorators.size() > 0 ? decorators : this.spanDecorators;
        }

        contextAttribute = servletContext.getAttribute(SKIP_PATTERN);
        if (contextAttribute instanceof Pattern) {
            skipPattern = (Pattern) contextAttribute;
        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;

        if (!isTraced(httpRequest, httpResponse)) {
            chain.doFilter(httpRequest, httpResponse);
            return;
        }

        /**
         * If request is traced then do not start new span.
         */

        System.out.println("*-* Server doFilter -- deniyoruz2");
        // //toslali: do not create span here -- trying something
        // if (true){
        // chain.doFilter(servletRequest, servletResponse);
        // return;
        // }

        if (servletRequest.getAttribute(SERVER_SPAN_CONTEXT) != null) {
            System.out.println("*-* Dofilter bir daha");
            chain.doFilter(servletRequest, servletResponse);
        } else {
            SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
                    new HttpServletRequestExtractAdapter(httpRequest));

            //tsl: check parent now
            
            Scope parentSpan = tracer.scopeManager().active();
            System.out.println("*-* PArent information: " + parentSpan== null ? "null" : parentSpan.span());

	    // System.out.println("*-*Server building span " + httpRequest.getMethod());

            final Scope scope = tracer.buildSpan(httpRequest.getMethod())
                    .asChildOf(extractedContext)
                    .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                    .startActive(false);

            System.out.println("*-* Server builded current span " + scope == null ? "null" : scope.span());

            // tsl: let's not make this span active, so that we can access parent context at TracingHandlerInterceptor
            // httpRequest.setAttribute(SERVER_SPAN_CONTEXT, scope.span().context());

            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
                spanDecorator.onRequest(httpRequest, scope.span());
            }
	        System.out.println("*-* do filter now after onrequest ");
           // final Scope scope = null;
            try {
                chain.doFilter(servletRequest, servletResponse);
                System.out.println("*-* after do filter now ");
                if (!httpRequest.isAsyncStarted()) {
                    for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
                        spanDecorator.onResponse(httpRequest, httpResponse, scope.span());
                    }
                }
                // catch all exceptions (e.g. RuntimeException, ServletException...)
            } catch (Throwable ex) {
                for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
                    spanDecorator.onError(httpRequest, httpResponse, ex, scope.span());
                }
                throw ex;
            } finally {
                if (httpRequest.isAsyncStarted()) {
                    // what if async is already finished? This would not be called
                    httpRequest.getAsyncContext()
                            .addListener(new AsyncListener() {
                        @Override
                        public void onComplete(AsyncEvent event) throws IOException {
                            HttpServletRequest httpRequest = (HttpServletRequest) event.getSuppliedRequest();
                            HttpServletResponse httpResponse = (HttpServletResponse) event.getSuppliedResponse();
                            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
                                    spanDecorator.onResponse(httpRequest,
                                    httpResponse,
                                    scope.span());
                            }
                            scope.span().finish();
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) throws IOException {
                            HttpServletRequest httpRequest = (HttpServletRequest) event.getSuppliedRequest();
                            HttpServletResponse httpResponse = (HttpServletResponse) event.getSuppliedResponse();
                            for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
                                  spanDecorator.onTimeout(httpRequest,
                                      httpResponse,
                                      event.getAsyncContext().getTimeout(),
                                      scope.span());
                              }
                        }

                        @Override
                        public void onError(AsyncEvent event) throws IOException {
                            HttpServletRequest httpRequest = (HttpServletRequest) event.getSuppliedRequest();
                            HttpServletResponse httpResponse = (HttpServletResponse) event.getSuppliedResponse();
                            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
                                spanDecorator.onError(httpRequest,
                                    httpResponse,
                                    event.getThrowable(),
                                    scope.span());
                            }
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) throws IOException {
                        }
                    });
                } else {
                    // If not async, then need to explicitly finish the span associated with the scope.
                    // This is necessary, as we don't know whether this request is being handled
                    // asynchronously until after the scope has already been started.
                    scope.span().finish();
                }
                scope.close();
            }
        }
    }

    @Override
    public void destroy() {
        this.filterConfig = null;
    }

    /**
     * It checks whether a request should be traced or not.
     *
     * @param httpServletRequest request
     * @param httpServletResponse response
     * @return whether request should be traced or not
     */
    protected boolean isTraced(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        // skip URLs matching skip pattern
        // e.g. pattern is defined as '/health|/status' then URL 'http://localhost:5000/context/health' won't be traced
        if (skipPattern != null) {
            String url = httpServletRequest.getRequestURI().substring(httpServletRequest.getContextPath().length());
            return !skipPattern.matcher(url).matches();
        }

        return true;
    }

    /**
     * Get context of server span.
     *
     * @param servletRequest request
     * @return server span context
     */
    public static SpanContext serverSpanContext(ServletRequest servletRequest) {
        return (SpanContext) servletRequest.getAttribute(SERVER_SPAN_CONTEXT);
    }
}
