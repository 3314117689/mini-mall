package com.mall.common.auth;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

/**
 * JWT 验签器（L6-4）：只拿公钥，验签不调用户服务。
 *
 * keyLocator 的用法：解析 token 时先读 header 的 kid，再用 kid 去 JWKS 里找公钥；
 * 找到后由 jjwt 校验签名和 exp。公钥缓存起来，后续请求不再拉 JWKS。
 */
public class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);

    private final String jwksUri;

    /** kid → 公钥；第一次验签时懒加载，之后直接命中 */
    private final Map<String, RSAPublicKey> keyCache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public JwtVerifier(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    /** 验签成功返回身份；没带 kid/签名错/过期都抛 4011 */
    public LoginUser verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .keyLocator(header -> resolveKey(String.valueOf(header.get("kid"))))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new LoginUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.get("role", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("token 校验失败: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    private RSAPublicKey resolveKey(String kid) {
        RSAPublicKey key = keyCache.get(kid);
        if (key == null) {
            refreshKeys();
            key = keyCache.get(kid);
        }
        if (key == null) {
            throw new IllegalArgumentException("JWKS 里找不到 kid=" + kid);
        }
        return key;
    }

    /** 拉一次 JWKS，把每个 kid 的 n/e 还原成 RSAPublicKey 放进缓存 */
    private synchronized void refreshKeys() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode keys = objectMapper.readTree(response.body()).path("keys");
            for (JsonNode jwk : keys) {
                BigInteger modulus = new BigInteger(1,
                        Base64.getUrlDecoder().decode(jwk.path("n").asText()));
                BigInteger exponent = new BigInteger(1,
                        Base64.getUrlDecoder().decode(jwk.path("e").asText()));
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                String kid = jwk.path("kid").asText();
                keyCache.put(kid, key);
                log.info("JWKS 已加载, kid={}", kid);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AUTH_UNAVAILABLE);
        } catch (Exception e) {
            log.error("拉取 JWKS 失败, uri={}", jwksUri, e);
            throw new BizException(ErrorCode.AUTH_UNAVAILABLE);
        }
    }
}
