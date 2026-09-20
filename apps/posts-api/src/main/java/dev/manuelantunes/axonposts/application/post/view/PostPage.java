package dev.manuelantunes.axonposts.application.post.view;

import java.util.List;

public record PostPage(List<PostView> items, long offset, boolean hasNext) {
    public PostPage {
        items = List.copyOf(items);
    }
}
