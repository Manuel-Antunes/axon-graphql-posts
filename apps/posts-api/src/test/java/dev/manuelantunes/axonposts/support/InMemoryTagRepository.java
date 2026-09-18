package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Repositório de Tags em memória, com a mesma busca por nome sem caixa do adapter real. */
public final class InMemoryTagRepository implements TagRepository {

    private final List<Tag> tags = new ArrayList<>();

    @Override
    public void save(Tag tag) {
        tags.add(tag);
    }

    /** O duplo do insert condicional: já existir é o resultado desejado, não um conflito. */
    @Override
    public void saveIfAbsent(Tag tag) {
        if (findById(tag.id()).isEmpty()) {
            tags.add(tag);
        }
    }

    @Override
    public Optional<Tag> findById(TagId tagId) {
        return tags.stream().filter(tag -> tag.id().equals(tagId)).findFirst();
    }

    @Override
    public List<Tag> findAllById(Collection<TagId> tagIds) {
        return tags.stream().filter(tag -> tagIds.contains(tag.id())).toList();
    }

    @Override
    public Optional<Tag> findByName(TagName name) {
        return tags.stream().filter(tag -> tag.name().sameAs(name)).findFirst();
    }

    @Override
    public boolean isEmpty() {
        return tags.isEmpty();
    }

    public List<Tag> all() {
        return List.copyOf(tags);
    }
}
