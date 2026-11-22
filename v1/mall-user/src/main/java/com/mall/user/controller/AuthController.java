package com.mall.user.controller;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mall.user.auth.JwtKeyLoader;

/**
 * 认证接口（L6）。/auth/** 是"拿门票"的地方，本身不需要 token。
 * 这里不用 Result 包一层：JWKS 是标准协议端点，前端/其他服务按 RFC 7517 解析。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private JwtKeyLoader jwtKeyLoader;

    /**
     * JWKS 公钥端点：公布验签公钥（只含公钥部分，泄露也无害）。
     * 调用方按 kid 找到这把钥匙，用 n、e 自己拼出 RSAPublicKey。
     */
    @GetMapping("/jwks")
    public Map<String, Object> jwks() {
        RSAPublicKey key = jwtKeyLoader.getPublicKey();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", jwtKeyLoader.getKid());
        // n = 模数，e = 公钥指数；JWK 要求 Base64URL 且不带填充
        jwk.put("n", base64Url(key.getModulus()));
        jwk.put("e", base64Url(key.getPublicExponent()));
        return Map.of("keys", List.of(jwk));
    }

    /** BigInteger.toByteArray() 最高位为 0 时会多一个符号字节，JWK 要求去掉 */
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
