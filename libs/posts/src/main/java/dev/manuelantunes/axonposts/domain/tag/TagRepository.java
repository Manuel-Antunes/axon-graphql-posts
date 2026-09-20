package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TagRepository {
    void save(Tag tag);

    void saveIfAbsent(Tag tag);

    Optional<Tag> findById(TagId tagId);

    Optional<Tag> findByName(TagName name);

    List<Tag> findAllById(Collection<TagId> tagIds);

    boolean isEmpty();
}
