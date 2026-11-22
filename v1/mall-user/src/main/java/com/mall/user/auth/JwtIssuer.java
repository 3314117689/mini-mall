package com.mall.user.auth;

import java.time.Instant;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mall.user.entity.User;

import io.jsonwebtoken.Jwts;

/**
 * 签发 JWT（L6-3）。
 *
 * 三个部分：header（alg/kid）· payload（sub/claims/iat/exp）· signature。
 * 签名用私钥，只有本服务有私钥；任何人拿公钥只能验签、不能伪造。
 */
@Component
public class JwtIssuer {

    @Autowired
    private JwtKeyLoader keyLoader;

    @Value("${mall.jwt.ttl-seconds}")
    private long ttlSeconds;

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                // kid 写进 header，验签方据此在 JWKS 里挑选公钥
                .header().keyId(keyLoader.getKid()).and()
                // sub 存用户 id：验签后 sub 就是可信的 userId
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("role", user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                // RS256：私钥签名，公钥验签
                .signWith(keyLoader.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
