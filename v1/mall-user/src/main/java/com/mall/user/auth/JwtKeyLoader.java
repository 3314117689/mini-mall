package com.mall.user.auth;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 启动期加载 RSA 密钥对（L6-1）。
 *
 * 为什么私钥只放在 mall-user：签名只能由发令牌的服务做；其他服务拿公钥验签即可。
 * 两个文件都是标准 PEM：私钥 PKCS#8（BEGIN PRIVATE KEY），公钥 X.509（BEGIN PUBLIC KEY）。
 */
@Component
public class JwtKeyLoader {

    /** 密钥编号：JWT 头部和 JWKS 里都带它，验签方按 kid 选对应的公钥 */
    private final String kid;

    private final PrivateKey privateKey;

    private final RSAPublicKey publicKey;

    public JwtKeyLoader(@Value("${mall.jwt.kid}") String kid,
            @Value("${mall.jwt.private-key}") Resource privateKeyResource,
            @Value("${mall.jwt.public-key}") Resource publicKeyResource) {
        this.kid = kid;
        try {
            this.privateKey = readPrivateKey(privateKeyResource);
            this.publicKey = readPublicKey(publicKeyResource);
        } catch (Exception e) {
            throw new IllegalStateException("加载 JWT 密钥失败，检查 mall.jwt.* 配置与 keys/ 下的 pem 文件", e);
        }
    }

    public String getKid() {
        return kid;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    private static PrivateKey readPrivateKey(Resource resource) throws Exception {
        byte[] der = decodePem(resource, "PRIVATE KEY");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey readPublicKey(Resource resource) throws Exception {
        byte[] der = decodePem(resource, "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** 去掉 PEM 头尾和所有空白，剩下的就是 DER 的 Base64；不做 ASN.1 解析 */
    private static byte[] decodePem(Resource resource, String type) throws Exception {
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String base64 = text
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
