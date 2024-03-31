package org.airsonic.player.spring;

import org.airsonic.player.util.NetworkUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

@Configuration
@EnableWebSocketMessageBroker
public class WebsocketConfiguration implements WebSocketMessageBrokerConfigurer {
    public static final String UNDERLYING_SERVLET_REQUEST = "servletRequest";
    public static final String USER_AGENT = "userAgent";
    public static final String UNDERLYING_HTTP_SESSION = "httpSession";
    public static final String BASE_URL = "baseUrl";

    private TaskScheduler messageBrokerTaskScheduler;
    private String contextPath;

    @Autowired
    public void setMessageBrokerTaskScheduler(@Lazy TaskScheduler taskScheduler) {
        this.messageBrokerTaskScheduler = taskScheduler;
    }

    @Autowired
    @Value("${server.servlet.context-path:/}")
    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(messageBrokerTaskScheduler)
                .setHeartbeatValue(new long[] { 20000, 20000 });
        config.setApplicationDestinationPrefixes("/app");

        // this ensures publish order is serial at the cost of no parallelization and
        // performance - if performance is bad, this should be turned off
        config.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/websocket")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new ServletRequestCaptureHandshakeInterceptor(contextPath))
                .withSockJS()
                .setClientLibraryUrl("../../script/sockjs-1.6.1.min.js");
    }

    public static class ServletRequestCaptureHandshakeInterceptor implements HandshakeInterceptor {
        private final String contextPath;

        public ServletRequestCaptureHandshakeInterceptor(String contextPath) {
            this.contextPath = contextPath;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
            // Set servlet request attribute to WebSocket session
            if (request instanceof ServletServerHttpRequest sshr) {
                attributes.put(UNDERLYING_SERVLET_REQUEST,
                        new WebsocketInterceptedServletRequest(sshr, contextPath));
                attributes.put(USER_AGENT, request.getHeaders().getFirst("User-Agent"));
                attributes.put(UNDERLYING_HTTP_SESSION,
                        ((ServletServerHttpRequest) request).getServletRequest().getSession(false));
                attributes.put(BASE_URL, NetworkUtil.getBaseUrl(sshr.getServletRequest()));
            }

            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                Exception exception) {
        }
    }

    public static class WebsocketInterceptedServletRequest implements HttpServletRequest {
        private final ServletServerHttpRequest originalRequest;
        private final String contextPath;

        public WebsocketInterceptedServletRequest(ServletServerHttpRequest originalRequest, String contextPath) {
            this.originalRequest = originalRequest;
            this.contextPath = contextPath;
        }

        public ServletServerHttpRequest getOriginalRequest() {
            return originalRequest;
        }

        @Override
        public Object getAttribute(String name) {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getAttribute(name))
                    .orElse(getOriginalRequest().getHeaders().get(name));
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return getOriginalRequest().getServletRequest().getAttributeNames();
        }

        @Override
        public String getCharacterEncoding() {
            return getOriginalRequest().getServletRequest().getCharacterEncoding();
        }

        @Override
        public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }

        @Override
        public String getContentType() {
            return getOriginalRequest().getServletRequest().getContentType();
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return getOriginalRequest().getServletRequest().getInputStream();
        }

        @Override
        public String getParameter(String name) {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getParameter(name))
                    .orElse(Optional.ofNullable(getOriginalRequest().getHeaders().get(name)).map(x -> x.get(0))
                            .orElse(null));
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return getOriginalRequest().getServletRequest().getParameterNames();
        }

        @Override
        public String[] getParameterValues(String name) {
            return getOriginalRequest().getServletRequest().getParameterValues(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return getOriginalRequest().getServletRequest().getParameterMap();
        }

        @Override
        public String getProtocol() {
            return getOriginalRequest().getServletRequest().getProtocol();
        }

        @Override
        public String getScheme() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getScheme())
                    .orElse(getOriginalRequest().getURI().getScheme());
        }

        @Override
        public String getServerName() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getServerName())
                    .orElse(getOriginalRequest().getURI().getHost());
        }

        @Override
        public int getServerPort() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getServerPort())
                    .orElse(getOriginalRequest().getURI().getPort());
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return null;
        }

        private <T> T getAddress(Supplier<T> addressSupplier, Function<URI, T> inCaseOfException) {
            try {
                return addressSupplier.get();
            } catch (Exception e) {
                return inCaseOfException.apply(getOriginalRequest().getURI());
            }
        }

        @Override
        public String getRemoteAddr() {
            return getAddress(() -> Optional.ofNullable(getOriginalRequest().getServletRequest().getRemoteAddr())
                    .orElse(getOriginalRequest().getRemoteAddress().getHostString()), URI::getHost);
        }

        @Override
        public String getRemoteHost() {
            return getAddress(() -> Optional.ofNullable(getOriginalRequest().getServletRequest().getRemoteHost())
                    .orElse(getOriginalRequest().getRemoteAddress().getHostName()), URI::getHost);
        }

        @Override
        public void setAttribute(String name, Object o) {
            if (getSession() == null) {
                return;
            }

            getSession().setAttribute(name, o);
        }

        @Override
        public void removeAttribute(String name) {
            if (getSession() == null) {
                return;
            }
            getSession().removeAttribute(name);
        }

        @Override
        public Locale getLocale() {
            return null;
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return null;
        }

        @Override
        public boolean isSecure() {
            return getOriginalRequest().getServletRequest().isSecure();
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            return null;
        }

        @Override
        public int getRemotePort() {
            return getAddress(() -> Optional.ofNullable(getOriginalRequest().getServletRequest().getRemotePort())
                    .orElse(getOriginalRequest().getRemoteAddress().getPort()), URI::getPort);
        }

        @Override
        public String getLocalName() {
            return getAddress(() -> Optional.ofNullable(getOriginalRequest().getServletRequest().getLocalName())
                    .orElse(getOriginalRequest().getLocalAddress().getHostName()), URI::getHost);
        }

        @Override
        public String getLocalAddr() {
            return getAddress(() -> Optional.ofNullable(getOriginalRequest().getServletRequest().getLocalAddr())
                    .orElse(getOriginalRequest().getLocalAddress().getHostString()), URI::getHost);
        }

        @Override
        public int getLocalPort() {
            return getAddress(() -> Optional.ofNullable(getOriginalRequest().getServletRequest().getLocalPort())
                    .orElse(getOriginalRequest().getLocalAddress().getPort()), URI::getPort);
        }

        @Override
        public ServletContext getServletContext() {
            return getOriginalRequest().getServletRequest().getServletContext();
        }

        @Override
        public AsyncContext startAsync() throws IllegalStateException {
            return null;
        }

        @Override
        public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
                throws IllegalStateException {
            return null;
        }

        @Override
        public boolean isAsyncStarted() {
            return false;
        }

        @Override
        public boolean isAsyncSupported() {
            return false;
        }

        @Override
        public AsyncContext getAsyncContext() {
            return null;
        }

        @Override
        public DispatcherType getDispatcherType() {
            return null;
        }

        @Override
        public String getAuthType() {
            return null;
        }

        @Override
        public Cookie[] getCookies() {
            return getOriginalRequest().getServletRequest().getCookies();
        }

        @Override
        public long getDateHeader(String name) {
            return 0;
        }

        @Override
        public String getHeader(String name) {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getHeader(name)).orElse(
                    Optional.ofNullable(getOriginalRequest().getHeaders().get(name)).map(x -> x.get(0)).orElse(null));
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getHeaders(name))
                    .orElse(Collections.enumeration(getOriginalRequest().getHeaders().get(name)));
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getHeaderNames())
                    .orElse(Collections.enumeration(getOriginalRequest().getHeaders().keySet()));
        }

        @Override
        public int getIntHeader(String name) {
            return 0;
        }

        @Override
        public String getMethod() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getMethod())
                    .orElse(getOriginalRequest().getMethod().name());
        }

        @Override
        public String getPathInfo() {
            return null;
        }

        @Override
        public String getPathTranslated() {
            return null;
        }

        @Override
        public String getContextPath() {
            return Optional
                    .ofNullable(StringUtils.trimToNull(getOriginalRequest().getServletRequest().getContextPath()))
                    .orElse(this.contextPath);
        }

        @Override
        public String getQueryString() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getQueryString())
                    .orElse(getOriginalRequest().getURI().getQuery());
        }

        @Override
        public String getRemoteUser() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getRemoteUser())
                    .orElse(getOriginalRequest().getPrincipal().getName());
        }

        @Override
        public boolean isUserInRole(String role) {
            return getOriginalRequest().getServletRequest().isUserInRole(role);
        }

        @Override
        public Principal getUserPrincipal() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getUserPrincipal())
                    .orElse(getOriginalRequest().getPrincipal());
        }

        @Override
        public String getRequestedSessionId() {
            return getOriginalRequest().getServletRequest().getRequestedSessionId();
        }

        @Override
        public String getRequestURI() {
            return Optional.ofNullable(getOriginalRequest().getServletRequest().getRequestURI())
                    .orElse(getOriginalRequest().getURI().toString());
        }

        @Override
        public StringBuffer getRequestURL() {
            return getAddress(() -> getOriginalRequest().getServletRequest().getRequestURL(),
                uri -> new StringBuffer(uri.toString()));
        }

        @Override
        public String getServletPath() {
            return getOriginalRequest().getServletRequest().getServletPath();
        }

        @Override
        public HttpSession getSession(boolean create) {
            return getOriginalRequest().getServletRequest().getSession(create);
        }

        @Override
        public HttpSession getSession() {
            return getOriginalRequest().getServletRequest().getSession();
        }

        @Override
        public String changeSessionId() {
            return getOriginalRequest().getServletRequest().changeSessionId();
        }

        @Override
        public boolean isRequestedSessionIdValid() {
            return getOriginalRequest().getServletRequest().isRequestedSessionIdValid();
        }

        @Override
        public boolean isRequestedSessionIdFromCookie() {
            return getOriginalRequest().getServletRequest().isRequestedSessionIdFromCookie();
        }

        @Override
        public boolean isRequestedSessionIdFromURL() {
            return getOriginalRequest().getServletRequest().isRequestedSessionIdFromURL();
        }

        @Override
        public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
            return getOriginalRequest().getServletRequest().authenticate(response);
        }

        @Override
        public void login(String username, String password) throws ServletException {
        }

        @Override
        public void logout() throws ServletException {
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            return null;
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            return null;
        }

        @Override
        public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
            return null;
        }

        @Override
        public String getProtocolRequestId() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getProtocolRequestId'");
        }

        @Override
        public String getRequestId() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getRequestId'");
        }

        @Override
        public ServletConnection getServletConnection() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getServletConnection'");
        }

    }
}
