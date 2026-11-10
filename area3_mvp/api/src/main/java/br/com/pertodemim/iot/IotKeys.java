package br.com.pertodemim.iot;

import br.com.pertodemim.domain.IotTipo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chaves dos dispositivos simulados. A chave é derivada (HMAC-SHA256) de uma semente + unidade + tipo,
 * então o simulador consegue calcular a mesma chave sem arquivo de credenciais. No banco fica só o
 * hash SHA-256. Em produção real, cada dispositivo teria uma chave aleatória própria.
 */
public final class IotKeys {

    private IotKeys() {}

    public static String derivar(String semente, long unidadeId, IotTipo tipo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(semente.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((unidadeId + ":" + tipo.name().toLowerCase()).getBytes(StandardCharsets.UTF_8));
            return "pdm_" + HexFormat.of().formatHex(h).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao derivar a chave do dispositivo", e);
        }
    }

    public static String hash(String chave) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(chave.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
