/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.fetcher.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.fetcher.api.Fetcher;
import io.gravitee.fetcher.api.FetcherException;
import io.gravitee.fetcher.gitlab.vertx.VertxCompletableFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.HttpUtils;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GitlabFetcher implements Fetcher{

    private static final Logger logger = LoggerFactory.getLogger(GitlabFetcher.class);

    private static final String HTTPS_SCHEME = "https";

    private GitlabFetcherConfiguration gitlabFetcherConfiguration;

    @Autowired
    private Vertx vertx;
    @Value("${httpClient.timeout:10000}")
    private int httpClientTimeout;
    @Value("${httpClient.proxy.type:HTTP}")
    private String httpClientProxyType;

    @Value("${httpClient.proxy.http.host:#{systemProperties['http.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpHost;
    @Value("${httpClient.proxy.http.port:#{systemProperties['http.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpPort;
    @Value("${httpClient.proxy.http.username:#{null}}")
    private String httpClientProxyHttpUsername;
    @Value("${httpClient.proxy.http.password:#{null}}")
    private String httpClientProxyHttpPassword;

    @Value("${httpClient.proxy.https.host:#{systemProperties['https.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpsHost;
    @Value("${httpClient.proxy.https.port:#{systemProperties['https.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpsPort;
    @Value("${httpClient.proxy.https.username:#{null}}")
    private String httpClientProxyHttpsUsername;
    @Value("${httpClient.proxy.https.password:#{null}}")
    private String httpClientProxyHttpsPassword;

    public GitlabFetcher(GitlabFetcherConfiguration gitlabFetcherConfiguration) {
        this.gitlabFetcherConfiguration = gitlabFetcherConfiguration;
    }

    @Override
    public InputStream fetch() throws FetcherException {
        if (gitlabFetcherConfiguration.getBranchOrTag() == null
                || gitlabFetcherConfiguration.getGitlabUrl() == null
                || gitlabFetcherConfiguration.getFilepath() == null
                || gitlabFetcherConfiguration.getNamespace() == null
                || gitlabFetcherConfiguration.getProject() == null) {
            throw new FetcherException("Some configuration attributes are null", null);
        }

        try {
            Buffer buffer = fetchContent().join();
            if (buffer == null || buffer.length() == 0) {
                logger.warn("Something goes wrong, Gitlab responds with a status 200 but the content is null.");
                return null;
            }

            JsonNode jsonNode = new ObjectMapper().readTree(buffer.getBytes());
            if (jsonNode != null) {
                JsonNode content = jsonNode.get("content");
                if (content != null) {
                    String contentAsBase64 = content.asText();
                    byte[] decodedContent = Base64.getDecoder().decode(contentAsBase64);
                    return new ByteArrayInputStream(decodedContent);
                }

                return null;
            }

            return new ByteArrayInputStream(buffer.getBytes());
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            throw new FetcherException("Unable to fetch Gitlab content (" + ex.getMessage() + ")", ex);
        }
    }

    private String getEncodedRequestUrl() throws UnsupportedEncodingException {
        String ref = ((gitlabFetcherConfiguration.getBranchOrTag() == null || gitlabFetcherConfiguration.getBranchOrTag().trim().isEmpty())
                        ? "master"
                        : gitlabFetcherConfiguration.getBranchOrTag().trim());

        String encodedProject = URLEncoder.encode(gitlabFetcherConfiguration.getNamespace().trim() + '/' + gitlabFetcherConfiguration.getProject().trim(), "UTF-8");

        switch (gitlabFetcherConfiguration.getApiVersion()) {
            case V4:
                return gitlabFetcherConfiguration.getGitlabUrl().trim()
                        + "/projects/" + encodedProject
                        + "/repository/files/" + URLEncoder.encode(gitlabFetcherConfiguration.getFilepath().trim(), "UTF-8")
                        + "?ref=" + ref;
            default:
                return gitlabFetcherConfiguration.getGitlabUrl().trim()
                        + "/projects/" + encodedProject
                        + "/repository/files"
                        + "?file_path=" + gitlabFetcherConfiguration.getFilepath().trim()
                        + "&ref=" + ref;
        }

    }

    private CompletableFuture<Buffer> fetchContent() throws Exception {
        CompletableFuture<Buffer> future = new VertxCompletableFuture<>(vertx);

        String url = getEncodedRequestUrl();

        URI requestUri = URI.create(url);
        boolean ssl = HTTPS_SCHEME.equalsIgnoreCase(requestUri.getScheme());

        final HttpClientOptions options = new HttpClientOptions()
                .setSsl(ssl)
                .setTrustAll(true)
                .setMaxPoolSize(1)
                .setKeepAlive(false)
                .setTcpKeepAlive(false)
                .setConnectTimeout(httpClientTimeout);

        if (gitlabFetcherConfiguration.isUseSystemProxy()) {
            ProxyOptions proxyOptions = new ProxyOptions();
            proxyOptions.setType(ProxyType.valueOf(httpClientProxyType));
            if (HTTPS_SCHEME.equals(requestUri.getScheme())) {
                proxyOptions.setHost(httpClientProxyHttpsHost);
                proxyOptions.setPort(httpClientProxyHttpsPort);
                proxyOptions.setUsername(httpClientProxyHttpsUsername);
                proxyOptions.setPassword(httpClientProxyHttpsPassword);
            } else {
                proxyOptions.setHost(httpClientProxyHttpHost);
                proxyOptions.setPort(httpClientProxyHttpPort);
                proxyOptions.setUsername(httpClientProxyHttpUsername);
                proxyOptions.setPassword(httpClientProxyHttpPassword);
            }
            options.setProxyOptions(proxyOptions);
        }

        final HttpClient httpClient = vertx.createHttpClient(options);

        httpClient.redirectHandler(resp -> {
            try {
                int statusCode = resp.statusCode();
                String location = resp.getHeader(HttpHeaders.LOCATION);
                if (location != null && (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307
                        || statusCode == 308)) {
                    HttpMethod m = resp.request().method();
                    if (statusCode == 301 || statusCode == 302 || statusCode == 303) {
                        m = HttpMethod.GET;
                    }
                    URI uri = HttpUtils.resolveURIReference(resp.request().absoluteURI(), location);
                    boolean redirectSsl;
                    int port = uri.getPort();
                    String protocol = uri.getScheme();
                    char chend = protocol.charAt(protocol.length() - 1);
                    if (chend == 'p') {
                        redirectSsl = false;
                        if (port == -1) {
                            port = 80;
                        }
                    } else if (chend == 's') {
                        redirectSsl = true;
                        if (port == -1) {
                            port = 443;
                        }
                    } else {
                        return null;
                    }
                    String requestURI = uri.getPath();
                    if (uri.getQuery() != null) {
                        requestURI += "?" + uri.getQuery();
                    }

                    RequestOptions requestOptions = new RequestOptions()
                            .setHost(uri.getHost())
                            .setPort(port)
                            .setSsl(redirectSsl)
                            .setURI(requestURI);

                    return Future.succeededFuture(httpClient.request(m, requestOptions));
                }
                return null;
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        });

        final int port = requestUri.getPort() != -1 ? requestUri.getPort() :
                (HTTPS_SCHEME.equals(requestUri.getScheme()) ? 443 : 80);

        try {
            HttpClientRequest request = httpClient.request(
                    HttpMethod.GET,
                    port,
                    requestUri.getHost(),
                    requestUri.toString()
            );

            // Follow redirect since Gitlab may return a 3xx status code
            request.setFollowRedirects(true);

            request.setTimeout(httpClientTimeout);

            if (gitlabFetcherConfiguration.getPrivateToken() != null && !gitlabFetcherConfiguration.getPrivateToken().trim().isEmpty()) {
                // Set GitLab token header
                request.putHeader("PRIVATE-TOKEN", gitlabFetcherConfiguration.getPrivateToken());
            }

            request.handler(response -> {
                if (response.statusCode() == HttpStatusCode.OK_200) {
                    response.bodyHandler(buffer -> {
                        future.complete(buffer);

                        // Close client
                        httpClient.close();
                    });
                } else {
                    future.completeExceptionally(new FetcherException("Unable to fetch '" + url + "'. Status code: " + response.statusCode() + ". Message: " + response.statusMessage(), null));
                }
            });

            request.exceptionHandler(event -> {
                try {
                    future.completeExceptionally(event);

                    // Close client
                    httpClient.close();
                } catch (IllegalStateException ise) {
                    // Do not take care about exception when closing client
                }
            });

            request.end();
        } catch (Exception ex) {
            logger.error("Unable to fetch content using HTTP", ex);
            future.completeExceptionally(ex);
        }

        return future;
    }

    public void setVertx(Vertx vertx) {
        this.vertx = vertx;
    }
}
