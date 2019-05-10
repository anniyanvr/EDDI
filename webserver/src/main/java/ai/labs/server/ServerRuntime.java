package ai.labs.server;

import ai.labs.runtime.SwaggerServletContextListener;
import ai.labs.runtime.ThreadContext;
import ai.labs.utilities.FileUtilities;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Slf4jLog;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.jboss.resteasy.jsapi.JSAPIServlet;
import org.jboss.resteasy.plugins.guice.GuiceResteasyBootstrapServletContextListener;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.adapters.servlet.KeycloakOIDCFilter;
import org.keycloak.representations.adapters.config.AdapterConfig;
import ro.isdc.wro.http.WroFilter;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;


/**
 * @author ginccc
 */
@Singleton
@Slf4j
public class ServerRuntime implements IServerRuntime {
    private static final String BASIC_AUTH_SECURITY_HANDLER_TYPE = "basic";

    public static class Options {
        public Class<?> applicationConfiguration;
        public LoginService loginService;
        public String host;
        public int httpPort;
        public int httpsPort;
        public String keyStorePath;
        public String keyStorePassword;
        public String defaultPath;
        public String[] virtualHosts;
        public boolean useCrossSiteScripting;
        public long responseDelayInMillis;
        public long idleTime;
        public int outputBufferSize;
        public String securityHandlerType;
    }

    private static final String ANY_PATH = "/*";

    private Options options;
    private final GuiceResteasyBootstrapServletContextListener resteasyContextListener;
    private final SwaggerServletContextListener swaggerContextListener;
    private final HttpServletDispatcher httpServletDispatcher;
    private final SecurityHandler securityHandler;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final MongoLoginService mongoLoginService;
    private final AdapterConfig keycloakAdapterConfig;
    private final String environment;
    private final String resourceDir;

    public ServerRuntime(Options options,
                         GuiceResteasyBootstrapServletContextListener resteasyContextListener,
                         SwaggerServletContextListener swaggerContextListener,
                         HttpServletDispatcher httpServletDispatcher,
                         SecurityHandler securityHandler,
                         ThreadPoolExecutor threadPoolExecutor,
                         MongoLoginService mongoLoginService,
                         AdapterConfig keycloakAdapterConfig,
                         @Named("system.environment") String environment,
                         @Named("systemRuntime.resourceDir") String resourceDir) {
        this.options = options;
        this.resteasyContextListener = resteasyContextListener;
        this.swaggerContextListener = swaggerContextListener;
        this.httpServletDispatcher = httpServletDispatcher;
        this.securityHandler = securityHandler;
        this.threadPoolExecutor = threadPoolExecutor;
        this.mongoLoginService = mongoLoginService;
        this.keycloakAdapterConfig = keycloakAdapterConfig;
        this.environment = environment;
        this.resourceDir = resourceDir;
        RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
    }

    @Override
    public void startup(final IStartupCompleteListener completeListener) {
        new Thread(ServerRuntime.class.getSimpleName()) {
            public void run() {
                try {
                    Map<String, String> contextParameter = new HashMap<>();
                    contextParameter.put("resteasy.guice.stage", environment.toUpperCase());
                    contextParameter.put("resteasy.logger.type", "SLF4J");
                    contextParameter.put("resteasy.servlet.mapping.prefix", "/");
                    contextParameter.put("javax.ws.rs.Application", options.applicationConfiguration.getName());

                    startupJetty(contextParameter,
                            Arrays.asList(resteasyContextListener, swaggerContextListener),
                            Arrays.asList(new FilterMappingHolder(
                                            new KeycloakOIDCFilter(
                                                    facade -> KeycloakDeploymentBuilder.build(keycloakAdapterConfig)), "/keycloak/*"),
                                    new FilterMappingHolder(new WroFilter(), "/text/*")),
                            Arrays.asList(new HttpServletHolder(httpServletDispatcher, "/*"),
                                    new HttpServletHolder(new JSAPIServlet(), "/rest-js")),
                            FileUtilities.buildPath(System.getProperty("user.dir"), resourceDir));
                    log.info("Jetty has successfully started.");
                    completeListener.onComplete();
                } catch (Exception e) {
                    log.error(e.getLocalizedMessage(), e);
                }
            }
        }.start();
    }

    private void startupJetty(Map<String, String> contextParameters,
                              List<EventListener> eventListeners,
                              final List<FilterMappingHolder> filters,
                              final List<HttpServletHolder> servlets,
                              final String resourcePath) throws Exception {

        Log.setLog(new Slf4jLog());

        HttpConfiguration config = new HttpConfiguration();
        config.addCustomizer(new SecureRequestCustomizer());
        config.setSecurePort(options.httpsPort);
        config.setSecureScheme("https");
        config.setSendServerVersion(false);
        config.setOutputBufferSize(options.outputBufferSize);

        HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(config);

        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol("h2");

        // SSL Connection Factory
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(options.keyStorePath);
        sslContextFactory.setKeyStorePassword(options.keyStorePassword);
        sslContextFactory.setKeyManagerPassword(options.keyStorePassword);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        sslContextFactory.setUseCipherSuitesOrder(true);
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

        HttpConnectionFactory http1 = new HttpConnectionFactory(config);

        Server server = new Server(createThreadPool());

        ServerConnector httpsConnector = new ServerConnector(server, ssl, alpn, http2, http1);
        httpsConnector.setPort(options.httpsPort);
        httpsConnector.setIdleTimeout(options.idleTime);

        ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(config));
        httpConnector.setPort(options.httpPort);
        httpConnector.setIdleTimeout(options.idleTime);

        server.setConnectors(new Connector[]{httpsConnector, httpConnector});

        // Set a handler
        final HandlerList handlers = new HandlerList();

        ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        servletHandler.setResourceBase(resourcePath);

        if (securityHandler != null) {
            servletHandler.setSecurityHandler(securityHandler);
        }

        handlers.addHandler(servletHandler);

        //set context params
        contextParameters.keySet().forEach(contextKey -> {
            final String contextValue = contextParameters.get(contextKey);
            servletHandler.setInitParameter(contextKey, contextValue);
        });

        //set event listeners
        eventListeners.forEach(servletHandler::addEventListener);

        filters.forEach(filter -> servletHandler.addFilter(
                new FilterHolder(filter.filter), filter.mappingPath, getAllDispatcherTypes()));

        servlets.forEach(httpServletHolder -> {
            Servlet servlet = httpServletHolder.getServlet();
            ServletHolder servletHolder = new ServletHolder(servlet);
            servletHolder.setInitParameters(httpServletHolder.getInitParameter());
            servletHandler.addServlet(servletHolder, httpServletHolder.getPath());
        });

        servletHandler.addFilter(new FilterHolder(createRedirectFilter(options.defaultPath)), ANY_PATH, getAllDispatcherTypes());

        if (BASIC_AUTH_SECURITY_HANDLER_TYPE.equals(options.securityHandlerType)) {
            if (securityHandler != null) {
                securityHandler.setLoginService(mongoLoginService);
                log.info("Basic Authentication has been enabled...");
            }
        }
        servletHandler.addFilter(new FilterHolder(createInitThreadBoundValuesFilter()), ANY_PATH, getAllDispatcherTypes());

        if (options.useCrossSiteScripting) {
            //add header param in order to enable cross-site-scripting
            servletHandler.addFilter(createCrossSiteScriptFilter(), ANY_PATH, getAllDispatcherTypes());
            log.info("CrossSiteScriptFilter has been enabled...");
        }

        server.setHandler(handlers);

        // Start the server
        server.setStopAtShutdown(true);
        server.start();
        //server.join();
    }

    private Filter createRedirectFilter(final String defaultPath) {
        return new Filter() {

            @Override
            public void init(FilterConfig filterConfig) {
                //not implemented
            }

            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
                HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
                StringBuffer requestURL = httpServletRequest.getRequestURL();

                if (requestURL != null) {

                    boolean changedPath;
                    changedPath = changeDefaultPath(requestURL, httpServletRequest, defaultPath);

                    if (changedPath) {
                        ((HttpServletResponse) servletResponse).sendRedirect(requestURL.toString());
                        return;
                    }
                }

                filterChain.doFilter(servletRequest, servletResponse);
            }

            private boolean changeDefaultPath(StringBuffer requestURL, HttpServletRequest request, String defaultPath) throws MalformedURLException {
                final String currentPath = request.getRequestURI();
                if (!currentPath.equals(defaultPath)) {
                    if ("/".equals(currentPath) || "".equals(currentPath)) {
                        StringBuilder redirectPath = new StringBuilder(defaultPath);
                        String queryString = request.getQueryString();
                        if (queryString != null) {
                            redirectPath.append("?").append(queryString);
                        }

                        URL origUrl = URI.create(requestURL.toString()).toURL();
                        URL newUrl = new URL(origUrl.getProtocol(), origUrl.getHost(), origUrl.getPort(), redirectPath.toString());
                        requestURL.replace(0, requestURL.length(), newUrl.toString());

                        return true;
                    }
                }

                return false;
            }

            @Override
            public void destroy() {
                //not implemented
            }
        };
    }

    private Filter createInitThreadBoundValuesFilter() {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) {
                //not implemented
            }

            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
                HttpURI httpURI;
                String requestUrl;
                if (servletRequest instanceof Request) {
                    httpURI = ((Request) servletRequest).getHttpURI();
                    requestUrl = ((Request) servletRequest).getRequestURL().toString();
                } else {
                    HttpServletRequest httpservletRequest = (HttpServletRequest) servletRequest;
                    requestUrl = httpservletRequest.getRequestURL().toString();
                    Request request = Request.getBaseRequest(((HttpServletRequestWrapper) httpservletRequest).getRequest());
                    httpURI = request.getHttpURI();
                }

                URL requestURL = URI.create(requestUrl).toURL();
                String currentResourceURI = httpURI.getPathQuery();
                ThreadContext.put("currentResourceURI", currentResourceURI);
                ThreadContext.put("currentURLProtocol", requestURL.getProtocol());
                ThreadContext.put("currentURLHost", requestURL.getHost());
                ThreadContext.put("currentURLPort", requestURL.getPort());

                filterChain.doFilter(servletRequest, servletResponse);

                ThreadContext.remove();
            }

            @Override
            public void destroy() {
                //not implemented
            }
        };
    }

    private FilterHolder createCrossSiteScriptFilter() {
        FilterHolder cors = new FilterHolder(CrossOriginFilter.class);
        cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
        cors.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
        cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "OPTIONS,HEAD,GET,PUT,POST,PATCH,DELETE");
        cors.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "Authorization,X-Requested-With,Content-Type,Accept,Origin,Cache-Control");
        cors.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_EXPOSE_HEADERS_HEADER, "location");

        return cors;
    }

    private static EnumSet<DispatcherType> getAllDispatcherTypes() {
        return EnumSet.allOf(DispatcherType.class);
    }

    private ThreadPool createThreadPool() {
        return new ExecutorThreadPool(threadPoolExecutor);
    }

    @AllArgsConstructor
    private static class FilterMappingHolder {
        private Filter filter;
        private String mappingPath;
    }
}
