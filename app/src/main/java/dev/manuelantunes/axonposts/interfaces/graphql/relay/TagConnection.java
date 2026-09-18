package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import dev.manuelantunes.axonposts.application.tag.view.TagView;

/** {@code type TagConnection { edges: [TagEdge]!, pageInfo: PageInfo! }}. Ver {@link PostConnection}. */
public final class TagConnection extends Connection<TagView, TagEdge> {
}
