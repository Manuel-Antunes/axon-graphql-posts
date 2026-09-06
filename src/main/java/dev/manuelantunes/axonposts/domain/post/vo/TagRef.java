package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

/**
 * A referência que o Post guarda de uma Tag: o id e o nome dela, copiados.
 * <p>
 * <b>Por que uma cópia e não um {@code @ManyToMany Tag}:</b> {@code Post} e {@code Tag} são agregados
 * distintos, cada um com o seu stream de eventos. Agregado referencia agregado <b>por identidade</b>,
 * nunca por objeto — uma associação JPA entre os dois criaria uma fronteira transacional compartilhada,
 * cascatas e lazy loading atravessando o limite de consistência, além de tornar impossível reconstruir
 * {@code Post} só a partir dos seus eventos.
 * <p>
 * O nome vem junto porque a lista de tags de um post é leitura pura: quem exibe o post não deveria ter
 * de carregar o agregado Tag para saber como ele se chama.
 * <p>
 * Campos são {@code String} e não {@code TagId}/{@code TagName}: atravessar a fronteira de um agregado é
 * como atravessar a fronteira de um processo — o que passa é dado, não o tipo do outro lado.
 */
@Embeddable
public record TagRef(String tagId, String name) {

    public TagRef {
        if (tagId == null || tagId.isBlank()) {
            throw new InvalidPostException("tagId não pode ser vazio");
        }
        if (name == null || name.isBlank()) {
            throw new InvalidPostException("nome da tag não pode ser vazio");
        }
        tagId = tagId.strip();
        name = name.strip();
    }

    public static TagRef of(String tagId, String name) {
        return new TagRef(tagId, name);
    }

    @Override
    public String toString() {
        return name;
    }
}
