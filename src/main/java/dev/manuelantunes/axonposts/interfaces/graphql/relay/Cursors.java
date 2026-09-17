package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import dev.manuelantunes.axonposts.interfaces.graphql.error.BadRequestException;

/**
 * Codec de cursores opacos.
 * <p>
 * Formato interno: {@code <tipo>:<offset>} em Base64-URL sem padding. O prefixo de tipo impede que um
 * cursor de {@code posts} seja aceito em {@code tags} — e, se algum dia o critério de ordenação mudar,
 * basta trocar o prefixo ({@code post} → {@code post2}) para invalidar cursores antigos de forma limpa.
 * <p>
 * Para o cliente o cursor é opaco: ele nunca deve montar um, só devolver o que recebeu. O
 * {@code CursorStrategy} do Spring codificava um {@code O_<offset>} em Base64, sem prefixo — um cursor de
 * uma conexão servia em qualquer outra, e a troca de esquema não tinha como ser sinalizada.
 */
public final class Cursors {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String SEPARATOR = ":";

    private Cursors() {
    }

    /** O cursor da linha de índice {@code offset} na conexão {@code type}. */
    public static String encode(String type, long offset) {
        String raw = type + SEPARATOR + offset;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodifica e valida o tipo. Qualquer problema vira um {@link BadRequestException} — cursor
     * malformado é erro de quem chamou, e o cliente recebe {@code code: BAD_REQUEST} com mensagem
     * legível, em vez de um {@code IllegalArgumentException} de Base64 vazando como erro interno.
     *
     * @return o índice da linha que este cursor aponta
     */
    public static long decode(String type, String cursor) {
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            String prefix = type + SEPARATOR;
            if (!raw.startsWith(prefix)) {
                throw new IllegalArgumentException("prefixo inesperado");
            }
            long offset = Long.parseLong(raw.substring(prefix.length()));
            if (offset < 0) {
                throw new IllegalArgumentException("posição negativa");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("cursor inválido: " + cursor);
        }
    }
}
