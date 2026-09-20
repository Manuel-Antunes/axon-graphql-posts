package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.event.UserRegisteredEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "authors")
@PrimaryKeyJoinColumn(name = "id")
@SQLDelete(sql = "update authors set id = id where id = ?")
public class Author extends User {
    @Column(name = "bio", length = 280, nullable = false)
    private String bio;

    protected Author() {
    }

    Author(UserRegisteredEvent event) {
        super(event);
        this.bio = normalized(event.bio());
    }

    public static Author reference(UserId id) {
        Author author = new Author();
        author.initReference(id);
        author.bio = "—";
        return author;
    }

    static String normalized(String bio) {
        return bio == null || bio.isBlank() ? "—" : bio.strip();
    }

    @Override
    public Set<Role> roles() {
        return EnumSet.of(Role.USER, Role.AUTHOR);
    }

    public String bio() {
        return bio;
    }

    @Override
    public String toString() {
        return Objects.toString(name(), String.valueOf(id()));
    }
}
